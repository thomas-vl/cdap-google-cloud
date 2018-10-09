/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.gcp.bigquery;

import co.cask.cdap.api.annotation.Description;
import co.cask.cdap.api.annotation.Name;
import co.cask.cdap.api.annotation.Plugin;
import co.cask.cdap.api.data.batch.Output;
import co.cask.cdap.api.data.batch.OutputFormatProvider;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.dataset.lib.KeyValue;
import co.cask.cdap.etl.api.Emitter;
import co.cask.cdap.etl.api.PipelineConfigurer;
import co.cask.cdap.etl.api.batch.BatchRuntimeContext;
import co.cask.cdap.etl.api.batch.BatchSink;
import co.cask.cdap.etl.api.batch.BatchSinkContext;
import co.cask.hydrator.common.LineageRecorder;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.DatasetInfo;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.Table;
import com.google.cloud.hadoop.io.bigquery.BigQueryFileFormat;
import com.google.cloud.hadoop.io.bigquery.output.BigQueryOutputConfiguration;
import com.google.cloud.hadoop.io.bigquery.output.BigQueryTableFieldSchema;
import com.google.cloud.hadoop.io.bigquery.output.BigQueryTableSchema;
import com.google.cloud.hadoop.io.bigquery.output.IndirectBigQueryOutputFormat;
import com.google.gson.JsonObject;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * This class <code>BigQuerySink</code> is a plugin that would allow users
 * to write <code>StructuredRecords</code> to Google Big Query.
 *
 * The plugin uses native BigQuery Output format to write data.
 */
@Plugin(type = "batchsink")
@Name(BigQuerySink.NAME)
@Description("This sink writes to a BigQuery table. "
  + "BigQuery is Google's serverless, highly scalable, enterprise data warehouse. "
  + "Data is first written to a temporary location on Google Cloud Storage, then loaded into BigQuery from there.")
public final class BigQuerySink extends BatchSink<StructuredRecord, JsonObject, NullWritable> {
  public static final String NAME = "BigQueryTable";
  private static final Logger LOG = LoggerFactory.getLogger(BigQuerySink.class);

  private final BigQuerySinkConfig config;
  private StructuredRecordToJsonTransformer transformer;
  private Configuration configuration;
  // UUID for the run. Will be used as bucket name if bucket is not provided.
  private UUID uuid;

  public BigQuerySink(BigQuerySinkConfig config) {
    this.config = config;
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) {
    super.configurePipeline(pipelineConfigurer);
    config.validate();
  }

  @Override
  public void prepareRun(BatchSinkContext context) throws Exception {
    BigQuery bigquery = BigQueryUtils.getBigQuery(config.getServiceAccountFilePath(), config.getProject());
    // create dataset if dataset does not exist
    if (bigquery.getDataset(config.dataset) == null) {
      try {
        bigquery.create(DatasetInfo.newBuilder(config.dataset).build());
      } catch (BigQueryException e) {
        throw new RuntimeException("Exception occurred while creating dataset " + config.dataset + ".", e);
      }
    }

    validateSchema();

    uuid = UUID.randomUUID();
    configuration = BigQueryUtils.getBigQueryConfig(config.getServiceAccountFilePath(), config.getProject());

    List<BigQueryTableFieldSchema> fields = new ArrayList<>();
    for (Schema.Field field : config.getSchema().getFields()) {
      String tableTypeName = BigQueryUtils.getTableDataType(
        BigQueryUtils.getNonNullableSchema(field.getSchema())).name();
      BigQueryTableFieldSchema tableFieldSchema = new BigQueryTableFieldSchema()
        .setName(field.getName())
        .setType(tableTypeName)
        .setMode(Field.Mode.NULLABLE.name());
      fields.add(tableFieldSchema);
    }

    String bucket = config.bucket;
    if (config.bucket == null) {
      bucket = uuid.toString();
      // By default, this option is false, meaning the job can not delete the bucket. So enable it only when bucket name
      // is not provided.
      configuration.setBoolean("fs.gs.bucket.delete.enable", true);
    }

    configuration.set("fs.gs.system.bucket", bucket);
    configuration.setBoolean("fs.gs.impl.disable.cache", true);
    configuration.setBoolean("fs.gs.metadata.cache.enable", false);
    String temporaryGcsPath = String.format("gs://%s/hadoop/input/%s", bucket, uuid);

    BigQueryOutputConfiguration.configure(
      configuration,
      String.format("%s.%s", config.dataset, config.table),
      new BigQueryTableSchema().setFields(fields),
      temporaryGcsPath,
      BigQueryFileFormat.NEWLINE_DELIMITED_JSON,
      TextOutputFormat.class);

    // Both emitLineage and setOutputFormat internally try to create an external dataset if it does not already exists.
    // We call emitLineage before since it creates the dataset with schema which .
    emitLineage(context, fields);
    setOutputFormat(context);
  }

  @Override
  public void initialize(BatchRuntimeContext context) throws Exception {
    super.initialize(context);
    transformer = new StructuredRecordToJsonTransformer(null);
  }

  @Override
  public void transform(StructuredRecord input, Emitter<KeyValue<JsonObject, NullWritable>> emitter) {
    emitter.emit(new KeyValue<>(transformer.transform(input), NullWritable.get()));
  }

  @Override
  public void onRunFinish(boolean succeeded, BatchSinkContext context) {
    if (config.bucket == null) {
      Path gcsPath = new Path(String.format("gs://%s", uuid.toString()));
      try {
        FileSystem fs = gcsPath.getFileSystem(configuration);
        if (fs.exists(gcsPath)) {
          fs.delete(gcsPath, true);
        }
      } catch (IOException e) {
        LOG.warn("Failed to delete bucket " + gcsPath.toUri().getPath() + ", " + e.getMessage());
      }
    }
  }

  private void setOutputFormat(BatchSinkContext context) {
    context.addOutput(Output.of(config.referenceName, new OutputFormatProvider() {
      @Override
      public String getOutputFormatClassName() {
        return IndirectBigQueryOutputFormat.class.getName();
      }

      @Override
      public Map<String, String> getOutputFormatConfiguration() {
        Map<String, String> config = new HashMap<>();
        for (Map.Entry<String, String> entry : configuration) {
          config.put(entry.getKey(), entry.getValue());
        }
        return config;
      }
    }));
  }

  private void emitLineage(BatchSinkContext context, List<BigQueryTableFieldSchema> fields) {
    LineageRecorder lineageRecorder = new LineageRecorder(context, config.referenceName);
    lineageRecorder.createExternalDataset(config.getSchema());

    if (!fields.isEmpty()) {
      lineageRecorder.recordWrite("Write", "Wrote to BigQuery table.",
                                  fields.stream().map(BigQueryTableFieldSchema::getName).collect(Collectors.toList()));
    }
  }

  /**
   * Validates output schema against bigquery table schema. It throws {@link IllegalArgumentException}
   * if the output schema has more fields than bigquery table or output schema field types does not match bigquery
   * column types.
   */
  private void validateSchema() throws IOException {
    Table table = BigQueryUtils.getBigQueryTable(config.getServiceAccountFilePath(), config.getProject(),
                                                 config.dataset, config.table);
    if (table == null) {
      // Table does not exist, so no further validation is required.
      return;
    }

    com.google.cloud.bigquery.Schema bqSchema = table.getDefinition().getSchema();
    if (bqSchema == null) {
      // Table is created without schema, so no further validation is required.
      return;
    }

    FieldList bqFields = bqSchema.getFields();
    List<Schema.Field> outputSchemaFields = config.getSchema().getFields();

    // Output schema should not have fields that are not present in BigQuery table.
    List<String> diff = BigQueryUtils.getSchemaMinusBqFields(outputSchemaFields, bqFields);
    if (!diff.isEmpty()) {
      throw new IllegalArgumentException(
        String.format("The output schema does not match the BigQuery table schema for '%s.%s' table. " +
                        "The table does not contain the '%s' column(s).", config.dataset, config.table, diff));
    }

    // validate the missing columns in output schema are nullable fields in bigquery
    List<String> remainingBQFields = BigQueryUtils.getBqFieldsMinusSchema(bqFields, outputSchemaFields);
    for (String field : remainingBQFields) {
      if (bqFields.get(field).getMode() != Field.Mode.NULLABLE) {
        throw new IllegalArgumentException(
          String.format("The output schema does not match the BigQuery table schema for '%s.%s'. " +
                          "The table requires column '%s', which is not in the output schema.",
                        config.dataset, config.table, field));
      }
    }

    // Match output schema field type with bigquery column type
    for (Schema.Field field : config.getSchema().getFields()) {
      validateSimpleTypes(field);
      BigQueryUtils.validateFieldSchemaMatches(bqFields.get(field.getName()), field, config.dataset, config.table);
    }
  }

  private void validateSimpleTypes(Schema.Field field) {
    String name = field.getName();
    Schema fieldSchema = BigQueryUtils.getNonNullableSchema(field.getSchema());
    Schema.Type type = fieldSchema.getType();

    // Complex types like arrays, maps and unions are not supported in BigQuery plugins.
    if (!type.isSimpleType()) {
      throw new IllegalArgumentException(String.format("Field '%s' is of unsupported type '%s'.", name, type));
    }
  }
}
