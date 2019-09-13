/*
 * Copyright © 2019 Cask Data, Inc.
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
package io.cdap.plugin.gcp.datastore.source;

import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.EntityQuery;
import com.google.cloud.datastore.EntityValue;
import com.google.cloud.datastore.FullEntity;
import com.google.cloud.datastore.Key;
import com.google.cloud.datastore.PathElement;
import com.google.cloud.datastore.Query;
import com.google.cloud.datastore.QueryResults;
import com.google.cloud.datastore.StructuredQuery;
import com.google.cloud.datastore.Value;
import com.google.cloud.datastore.ValueType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.api.data.batch.Input;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.api.dataset.lib.KeyValue;
import io.cdap.cdap.etl.api.Emitter;
import io.cdap.cdap.etl.api.FailureCollector;
import io.cdap.cdap.etl.api.PipelineConfigurer;
import io.cdap.cdap.etl.api.StageConfigurer;
import io.cdap.cdap.etl.api.batch.BatchRuntimeContext;
import io.cdap.cdap.etl.api.batch.BatchSource;
import io.cdap.cdap.etl.api.batch.BatchSourceContext;
import io.cdap.plugin.common.LineageRecorder;
import io.cdap.plugin.gcp.datastore.source.util.DatastoreSourceConstants;
import io.cdap.plugin.gcp.datastore.util.DatastoreUtil;
import org.apache.hadoop.io.NullWritable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Batch Datastore Source Plugin reads the data from Google Cloud Datastore.
 */
@Plugin(type = BatchSource.PLUGIN_TYPE)
@Name(DatastoreSource.NAME)
@Description("Google Cloud Datastore is a NoSQL document database built for automatic scaling and high performance. "
  + "Source plugin provides ability to read data from it by Kind with various filters usage.")
public class DatastoreSource extends BatchSource<NullWritable, Entity, StructuredRecord> {

  private static final Logger LOG = LoggerFactory.getLogger(DatastoreSource.class);
  public static final String NAME = "Datastore";

  private static final Map<ValueType, Schema> SUPPORTED_SIMPLE_TYPES = new ImmutableMap.Builder<ValueType, Schema>()
    .put(ValueType.STRING, Schema.of(Schema.Type.STRING))
    .put(ValueType.LONG, Schema.of(Schema.Type.LONG))
    .put(ValueType.DOUBLE, Schema.of(Schema.Type.DOUBLE))
    .put(ValueType.BOOLEAN, Schema.of(Schema.Type.BOOLEAN))
    .put(ValueType.TIMESTAMP, Schema.of(Schema.LogicalType.TIMESTAMP_MICROS))
    .put(ValueType.BLOB, Schema.of(Schema.Type.BYTES))
    .put(ValueType.NULL, Schema.of(Schema.Type.NULL))
    .build();

  private final DatastoreSourceConfig config;
  private EntityToRecordTransformer entityToRecordTransformer;

  public DatastoreSource(DatastoreSourceConfig config) {
    this.config = config;
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) {
    LOG.debug("Validate config during `configurePipeline` stage: {}", config);
    StageConfigurer stageConfigurer = pipelineConfigurer.getStageConfigurer();
    FailureCollector collector = stageConfigurer.getFailureCollector();
    config.validate(collector);
    // Since we have validated all the properties, throw an exception if there are any errors in the collector. This is
    // to avoid adding same validation errors again in getSchema method call
    collector.getOrThrowException();

    Schema configuredSchema = config.getSchema(collector);
    if (!config.shouldConnect()) {
      stageConfigurer.setOutputSchema(configuredSchema);
      return;
    }

    Schema schema = getSchema(collector);
    if (configuredSchema == null) {
      stageConfigurer.setOutputSchema(schema);
      return;
    }

    pipelineConfigurer.getStageConfigurer().setOutputSchema(configuredSchema);
  }

  @Override
  public void prepareRun(BatchSourceContext batchSourceContext) {
    LOG.debug("Validate config during `prepareRun` stage: {}", config);
    FailureCollector collector = batchSourceContext.getFailureCollector();
    config.validate(collector);
    collector.getOrThrowException();

    String project = config.getProject();
    String serviceAccountFile = config.getServiceAccountFilePath();
    String namespace = config.getNamespace();
    String kind = config.getKind();
    String pbQuery = config.constructPbQuery(collector).toString();
    String splits = String.valueOf(config.getNumSplits());

    batchSourceContext.setInput(
      Input.of(config.getReferenceName(),
               new DatastoreInputFormatProvider(project, serviceAccountFile, namespace, kind, pbQuery, splits)));

    Schema schema = batchSourceContext.getOutputSchema();
    LineageRecorder lineageRecorder = new LineageRecorder(batchSourceContext, config.getReferenceName());
    lineageRecorder.createExternalDataset(schema);
    lineageRecorder.recordRead("Read", "Read from Cloud Datastore.",
                               Objects.requireNonNull(schema.getFields()).stream()
                                 .map(Schema.Field::getName)
                                 .collect(Collectors.toList()));
  }

  @Override
  public void initialize(BatchRuntimeContext context) throws Exception {
    super.initialize(context);
    entityToRecordTransformer = new EntityToRecordTransformer(context.getOutputSchema(),
                                                              config.getKeyType(context.getFailureCollector()),
                                                              config.getKeyAlias());
  }

  @Override
  public void transform(KeyValue<NullWritable, Entity> input, Emitter<StructuredRecord> emitter) {
    Entity entity = input.getValue();
    StructuredRecord record = entityToRecordTransformer.transformEntity(entity);
    emitter.emit(record);
  }

  private Schema getSchema(FailureCollector collector) {
    EntityQuery.Builder queryBuilder = Query.newEntityQueryBuilder()
      .setNamespace(config.getNamespace())
      .setKind(config.getKind())
      .setLimit(1);

    Key ancestorKey = constructAncestorKey(config, collector);
    if (ancestorKey != null) {
      queryBuilder.setFilter(StructuredQuery.PropertyFilter.hasAncestor(ancestorKey));
    }
    EntityQuery query = queryBuilder.build();
    LOG.debug("Executing query for `Get Schema`: {}", query);

    Datastore datastore = DatastoreUtil.getDatastore(config.getServiceAccountFilePath(), config.getProject());
    QueryResults<Entity> results = datastore.run(query);

    if (results.hasNext()) {
      Entity entity = results.next();
      return constructSchema(entity, config.isIncludeKey(collector), config.getKeyAlias());
    }

    collector.addFailure("Cloud Datastore query did not return any results. ",
                         "Ensure Namespace, Kind and Ancestor properties are correct.")
      .withConfigProperty(DatastoreSourceConstants.PROPERTY_NAMESPACE)
      .withConfigProperty(DatastoreSourceConstants.PROPERTY_KIND)
      .withConfigProperty(DatastoreSourceConstants.PROPERTY_ANCESTOR);

    throw collector.getOrThrowException();
  }

  /**
   * Constructs ancestor key using using given Datastore configuration.
   *
   * @param config Datastore configuration
   * @param collector failure collector
   * @return Datastore key instance
   */
  @Nullable
  private Key constructAncestorKey(DatastoreSourceConfig config, FailureCollector collector) {
    List<PathElement> ancestor = config.getAncestor(collector);

    if (ancestor.isEmpty()) {
      return null;
    }

    PathElement keyElement = ancestor.get(ancestor.size() - 1);
    Key.Builder keyBuilder;
    if (keyElement.hasId()) {
      keyBuilder = Key.newBuilder(config.getProject(), keyElement.getKind(), keyElement.getId());
    } else {
      keyBuilder = Key.newBuilder(config.getProject(), keyElement.getKind(), keyElement.getName());
    }

    if (ancestor.size() > 1) {
      ancestor.subList(0, ancestor.size() - 1).forEach(keyBuilder::addAncestor);
    }
    return keyBuilder.setNamespace(config.getNamespace()).build();
  }


  /**
   * Constructs CDAP schema based on given CDAP entity and source configuration,
   * will add Datastore key to the list of schema fields if config include key flag is set to true.
   *
   * @param entity Datastore entity
   * @param isIncludeKey flag that indicates that key should be included in schema
   * @param keyName key name
   * @return CDAP schema
   */
  @VisibleForTesting
  Schema constructSchema(Entity entity, boolean isIncludeKey, String keyName) {
    List<Schema.Field> fields = constructSchemaFields(entity);

    if (isIncludeKey) {
      fields.add(Schema.Field.of(keyName, Schema.of(Schema.Type.STRING)));
    }

    return Schema.recordOf("schema", fields);
  }

  /**
   * Constructs list of CDAP schema fields based on given Datastore entity,
   * filters out fields schemas with null value.
   *
   * @param entity Datastore entity
   * @return list of CDAP schema fields
   */
  private List<Schema.Field> constructSchemaFields(FullEntity<?> entity) {
    return entity.getNames().stream()
      .map(name -> transformToField(name, entity.getValue(name)))
      .filter(Objects::nonNull)
      .collect(Collectors.toList());
  }

  /**
   * Since Datastore is schemaless database, creates field with nullable schema for the given value
   * based on its value type, for unsupported types returns null.
   *
   * @param name field name
   * @param value Datastore value
   * @return CDAP field
   */
  private Schema.Field transformToField(String name, Value<?> value) {
    Schema schema = createSchema(name, value);
    if (schema == null) {
      return null;
    }
    return Schema.Type.NULL == schema.getType()
      ? Schema.Field.of(name, schema)
      : Schema.Field.of(name, Schema.nullableOf(schema));
  }

  /**
   * Creates CDAP schema based on given Datastore value and its type,
   * for unsupported types will return null.
   *
   * @param name field name
   * @param value Datastore value
   * @return CDAP schema
   */
  private Schema createSchema(String name, Value<?> value) {
    Schema schema = SUPPORTED_SIMPLE_TYPES.get(value.getType());

    if (schema != null) {
      return schema;
    }

    switch (value.getType()) {
      case ENTITY:
        List<Schema.Field> fields = constructSchemaFields(((EntityValue) value).get());
        return Schema.recordOf(name, fields);
      case LIST:
        @SuppressWarnings("unchecked")
        List<? extends Value<?>> values = (List<? extends Value<?>>) value.get();
        Set<Schema> arraySchemas = new HashSet<>();
        for (Value<?> val : values) {
          Schema valSchema = createSchema(name, val);
          if (valSchema == null) {
            return null;
          }
          arraySchemas.add(valSchema);
        }

        if (arraySchemas.isEmpty()) {
          return Schema.arrayOf(Schema.of(Schema.Type.NULL));
        }

        if (arraySchemas.size() == 1) {
          Schema componentSchema = arraySchemas.iterator().next();
          return Schema.Type.NULL == componentSchema.getType()
            ? Schema.arrayOf(componentSchema)
            : Schema.arrayOf(Schema.nullableOf(componentSchema));
        }

        LOG.debug("Field '{}' has several schemas in array, add them as union of schemas "
                    + "plus {} schema for null values", name, Schema.Type.NULL);
        arraySchemas.add(Schema.of(Schema.Type.NULL));
        return Schema.arrayOf(Schema.unionOf(arraySchemas));
    }

    LOG.debug("Field '{}' is of unsupported type '{}', skipping field from the schema", name, value.getType());
    return null;
  }

}
