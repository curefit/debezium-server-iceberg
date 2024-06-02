/*
 *
 *  * Copyright memiiso Authors.
 *  *
 *  * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 *
 */

package io.debezium.server.iceberg;

import io.debezium.DebeziumException;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import io.debezium.serde.DebeziumSerdes;
import io.debezium.server.BaseChangeConsumer;
import io.debezium.server.iceberg.batchsizewait.InterfaceBatchSizeWait;
import io.debezium.server.iceberg.tableoperator.IcebergTableOperator;
import io.debezium.server.iceberg.tableoperator.IcebergTableWriterFactory;
import io.debezium.server.iceberg.tableoperator.PartitionedAppendWriter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.*;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericAppenderFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.BaseTaskWriter;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.io.WriteResult;
import org.apache.iceberg.types.Types;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT;
import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT_DEFAULT;
import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.apache.iceberg.types.Types.NestedField.required;

/**
 * Implementation of the consumer that delivers the messages to iceberg table.
 *
 * @author Ismail Simsek
 */
@Named("iceberg")
@Dependent
public class IcebergEventsChangeConsumer extends BaseChangeConsumer implements DebeziumEngine.ChangeConsumer<ChangeEvent<Object, Object>> {

  protected static final DateTimeFormatter dtFormater = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
  protected static final ObjectMapper mapper = new ObjectMapper();
  protected static final Serde<JsonNode> valSerde = DebeziumSerdes.payloadJson(JsonNode.class);
  protected static final Serde<JsonNode> keySerde = DebeziumSerdes.payloadJson(JsonNode.class);
  static final Schema TABLE_SCHEMA = new Schema(
      required(1, "event_destination", Types.StringType.get()),
      optional(2, "event_key_schema", Types.StringType.get()),
      optional(3, "event_key_payload", Types.StringType.get()),
      optional(4, "event_value_schema", Types.StringType.get()),
      optional(5, "event_value_payload", Types.StringType.get()),
      optional(6, "event_sink_epoch_ms", Types.LongType.get()),
      optional(7, "event_sink_timestamptz", Types.TimestampType.withZone())
  );

  static final Schema NEW_TABLE_SCHEMA = new Schema(
          optional(1, "event_key", Types.StringType.get()),
          optional(2, "event_value", Types.StringType.get()),
          optional(3, "event_sink_timestamptz", Types.TimestampType.withZone()),
          optional(4,"event_destination", Types.StringType.get()),
          optional(5, "event_sink_epoch_ms", Types.LongType.get())
  );

  static final PartitionSpec TABLE_PARTITION = PartitionSpec.builderFor(NEW_TABLE_SCHEMA)
      .identity("event_destination")
      .hour("event_sink_timestamptz")
      .build();
  static final SortOrder TABLE_SORT_ORDER = SortOrder.builderFor(NEW_TABLE_SCHEMA)
      .asc("event_sink_epoch_ms", NullOrder.NULLS_LAST)
      .asc("event_sink_timestamptz", NullOrder.NULLS_LAST)
      .build();
  private static final Logger LOGGER = LoggerFactory.getLogger(IcebergEventsChangeConsumer.class);
  private static final String PROP_PREFIX = "debezium.sink.iceberg.";
  static Deserializer<JsonNode> valDeserializer;
  static Deserializer<JsonNode> keyDeserializer;
  final Configuration hadoopConf = new Configuration();
  final Map<String, String> icebergProperties = new ConcurrentHashMap<>();
  @ConfigProperty(name = "debezium.sink.iceberg." + CatalogProperties.WAREHOUSE_LOCATION)
  String warehouseLocation;
  @ConfigProperty(name = "debezium.format.value", defaultValue = "json")
  String valueFormat;
  @ConfigProperty(name = "debezium.format.key", defaultValue = "json")
  String keyFormat;
  @ConfigProperty(name = "debezium.sink.iceberg.table-namespace", defaultValue = "default")
  String namespace;
  @ConfigProperty(name = "debezium.sink.iceberg.catalog-name", defaultValue = "default")
  String catalogName;
  @ConfigProperty(name = "debezium.sink.iceberg.table-prefix", defaultValue = "")
  Optional<String> tablePrefix;
  @ConfigProperty(name = "debezium.sink.iceberg." + DEFAULT_FILE_FORMAT, defaultValue = DEFAULT_FILE_FORMAT_DEFAULT)
  String writeFormat;
  @ConfigProperty(name = "debezium.sink.batch.batch-size-wait", defaultValue = "NoBatchSizeWait")
  String batchSizeWaitName;
  @ConfigProperty(name = "debezium.sink.iceberg.destination-regexp", defaultValue = "")
  protected Optional<String> destinationRegexp;
  @ConfigProperty(name = "debezium.sink.iceberg.destination-regexp-replace", defaultValue = "")
  protected Optional<String> destinationRegexpReplace;
  @Inject
  @Any
  Instance<InterfaceBatchSizeWait> batchSizeWaitInstances;
  @Inject
  IcebergTableWriterFactory writerFactory;
  InterfaceBatchSizeWait batchSizeWait;
  Catalog icebergCatalog;
  Table eventTable;

  @Inject
  IcebergTableOperator icebergTableOperator;

  @PostConstruct
  void connect() {
    if (!valueFormat.equalsIgnoreCase(Json.class.getSimpleName().toLowerCase())) {
      throw new DebeziumException("debezium.format.value={" + valueFormat + "} not supported, " +
                                  "Supported (debezium.format.value=*) formats are {json,}!");
    }
    if (!keyFormat.equalsIgnoreCase(Json.class.getSimpleName().toLowerCase())) {
      throw new DebeziumException("debezium.format.key={" + valueFormat + "} not supported, " +
                                  "Supported (debezium.format.key=*) formats are {json,}!");
    }

    Map<String, String> conf = IcebergUtil.getConfigSubset(ConfigProvider.getConfig(), PROP_PREFIX);
    conf.forEach(this.hadoopConf::set);
    this.icebergProperties.putAll(conf);

    icebergCatalog = CatalogUtil.buildIcebergCatalog(catalogName, icebergProperties, hadoopConf);

    batchSizeWait = IcebergUtil.selectInstance(batchSizeWaitInstances, batchSizeWaitName);
    batchSizeWait.initizalize();

    // configure and set 
    valSerde.configure(Collections.emptyMap(), false);
    valDeserializer = valSerde.deserializer();
    // configure and set 
    keySerde.configure(Collections.emptyMap(), true);
    keyDeserializer = keySerde.deserializer();

    LOGGER.info("Using {}", batchSizeWait.getClass().getName());
  }

  public GenericRecord getIcebergRecord(ChangeEvent<Object, Object> record, OffsetDateTime batchTime) {

    try {

      GenericRecord rec = GenericRecord.create(NEW_TABLE_SCHEMA.asStruct());
      rec.setField("event_destination", record.destination());
      rec.setField("event_key", record.key());
      rec.setField("event_value", record.value());
      rec.setField("event_sink_timestamptz", batchTime);
      rec.setField("event_sink_epoch_ms", batchTime.toEpochSecond());
      return rec;
    } catch (Exception e) {
      throw new DebeziumException(e);
    }
  }

  public String map(String destination) {
    return destination.replace(".", "_");
  }

  @Override
  public void handleBatch(final List<ChangeEvent<Object, Object>> records,
                          final DebeziumEngine.RecordCommitter<ChangeEvent<Object, Object>> committer)
      throws InterruptedException {
    Instant start = Instant.now();

    OffsetDateTime batchTime = OffsetDateTime.now(ZoneOffset.UTC);

//    Map<String, List<Map.Entry<ChangeEvent<Object, Object>, GenericRecord>>> groupedRecords = records.stream()
//            .map(e -> {
//              try {
//                return new AbstractMap.SimpleEntry<>(e, getIcebergRecord(e, batchTime));
//              } catch (Exception ex) {
//                ex.printStackTrace();
//                return null;
//              }
//            })
//            .filter(Objects::nonNull) // filter out nulls that may have resulted from exceptions
//            .collect(Collectors.groupingBy(
//                    e -> e.getValue().getField("event_destination").toString()
//            ));
//
//    for (Map.Entry<String, List<Map.Entry<ChangeEvent<Object, Object>, GenericRecord>>> tableEvents : groupedRecords.entrySet()) {
//      String table = tableEvents.getKey();
//      int lastPeriodIndex = table.lastIndexOf('.');
//      table = lastPeriodIndex != -1 ? table.substring(lastPeriodIndex + 1) : table;
//
//      if (!table.isEmpty()) {
//        Table icebergTable = this.loadIcebergTable(mapDestination(tableEvents.getKey()));
//        try (BaseTaskWriter<Record> writer = writerFactory.create(icebergTable)) {
//          for (Map.Entry<ChangeEvent<Object, Object>, GenericRecord> e : tableEvents.getValue()) {
//            writer.write(e.getValue());
//            committer.markProcessed(e.getKey());
//          }
//
//          writer.close();
//          WriteResult files = writer.complete();
//          AppendFiles appendFiles = icebergTable.newAppend();
//          Arrays.stream(files.dataFiles()).forEach(appendFiles::appendFile);
//          appendFiles.commit();
//        } catch (IOException ex) {
//          throw new DebeziumException(ex);
//        } catch (InterruptedException e) {
//          throw new RuntimeException(e);
//        }
//
//        LOGGER.info("Committed {} events to table! {}", tableEvents.getValue().size(), icebergTable.location());
////        icebergTableOperator.addRecordsToTableAndCommit(icebergTable, tableEvents.getValue(), committer);
//
//      }
//
//    }

    Map<String,ArrayList<Record>> result = records.stream()
            .map(e
            -> {
              try {
                return getIcebergRecord(e, batchTime);
              } catch (Exception ex) {
                ex.printStackTrace();
                return null;
              }
            }).collect(Collectors.groupingBy(e -> e.getField("event_destination").toString(), Collectors.toCollection(ArrayList::new)));

    for (Map.Entry<String, ArrayList<Record>> tableEvents : result.entrySet()) {
      String table = "";
      int lastPeriodIndex = (tableEvents.getKey()).lastIndexOf('.');
      table = (tableEvents.getKey()).substring(lastPeriodIndex + 1);
      if (lastPeriodIndex != -1 && !table.isEmpty()) {
        Table icebergTable = this.loadIcebergTable(mapDestination(tableEvents.getKey()));
        icebergTableOperator.addRecordsToTable(icebergTable, tableEvents.getValue());
      }
    }

    for (ChangeEvent<Object, Object> record : records) {
      LOGGER.trace("Processed event '{}'", record);
      committer.markProcessed(record);
    }

    committer.markBatchFinished();

    batchSizeWait.waitMs(records.size(), (int) Duration.between(start, Instant.now()).toMillis());
  }

  public TableIdentifier mapDestination(String destination) {

    // Extract the table name and strip topic and db name
    String table = "";
    int lastPeriodIndex = destination.lastIndexOf('.');
    if (lastPeriodIndex != -1) {
      table = destination.substring(lastPeriodIndex + 1);
      return TableIdentifier.of(Namespace.of(namespace),tablePrefix.orElse("") + "_" + table);
    }

    // If no period found, use the whole event_destination key as table name.
    // It will fail for db name with special characters.
    final String tableName = destination
            .replaceAll(destinationRegexp.orElse(""), destinationRegexpReplace.orElse(""))
            .replace(".", "_");

    return TableIdentifier.of(Namespace.of(namespace),tablePrefix.orElse("") + "_" +  tableName);
  }

  public Table loadIcebergTable(TableIdentifier tableId) {
    return IcebergUtil.loadIcebergTable(icebergCatalog, tableId).orElseGet(() -> {
      try {
        return IcebergUtil.createIcebergTable(icebergCatalog, tableId, NEW_TABLE_SCHEMA, writeFormat,
                true, // partition if its append mode
                "event_sink_timestamptz");
      } catch (Exception e){
        throw new DebeziumException("Failed to create table from debezium event schema:"+tableId+" Error:" + e.getMessage(), e);
      }
    });
  }

}
