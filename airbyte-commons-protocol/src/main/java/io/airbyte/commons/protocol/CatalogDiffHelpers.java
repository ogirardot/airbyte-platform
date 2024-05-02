/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import io.airbyte.commons.protocol.transform_models.FieldTransform;
import io.airbyte.commons.protocol.transform_models.StreamTransform;
import io.airbyte.commons.protocol.transform_models.UpdateFieldSchemaTransform;
import io.airbyte.commons.protocol.transform_models.UpdateStreamTransform;
import io.airbyte.protocol.models.AirbyteCatalog;
import io.airbyte.protocol.models.AirbyteStream;
import io.airbyte.protocol.models.CatalogHelpers;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.ConfiguredAirbyteStream;
import io.airbyte.protocol.models.DestinationSyncMode;
import io.airbyte.protocol.models.JsonSchemas;
import io.airbyte.protocol.models.Jsons;
import io.airbyte.protocol.models.StreamDescriptor;
import io.airbyte.protocol.models.SyncMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Helper class to compute catalog and stream diffs.
 */
public class CatalogDiffHelpers {

  private static final String ITEMS_KEY = "items";

  /**
   * Extracts all field names from a JSONSchema.
   *
   * @param jsonSchema - a JSONSchema node
   * @return a set of all keys for all objects within the node
   */
  @VisibleForTesting
  protected static Set<String> getAllFieldNames(final JsonNode jsonSchema) {
    return getFullyQualifiedFieldNamesWithTypes(jsonSchema)
        .stream()
        .map(Pair::getLeft)
        // only need field name, not fully qualified name
        .map(CatalogDiffHelpers::last)
        .flatMap(Optional::stream)
        .collect(Collectors.toSet());
  }

  /**
   * Get the last element of a list as an optional.
   *
   * @return returns empty optional if the list is empty or if the last element in the list is null.
   */
  private static Optional<String> last(final List<String> list) {
    if (list.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(list.get(list.size() - 1));
  }

  /**
   * Extracts all fields and their schemas from a JSONSchema. This method returns values in
   * depth-first search preorder. It short-circuits at oneOfs--in other words, child fields of a oneOf
   * are not returned.
   *
   * @param jsonSchema - a JSONSchema node
   * @return a list of all keys for all objects within the node. ordered in depth-first search
   *         preorder.
   */
  @VisibleForTesting
  protected static List<Pair<List<String>, JsonNode>> getFullyQualifiedFieldNamesWithTypes(
                                                                                           final JsonNode jsonSchema) {
    // if this were ever a performance issue, it could be replaced with a trie. this seems unlikely,
    // however.
    final Set<List<String>> fieldNamesThatAreOneOfs = new HashSet<>();

    return JsonSchemas.traverseJsonSchemaWithCollector(jsonSchema, (node, basicPath) -> {
      final List<String> fieldName = basicPath.stream()
          .map(fieldOrList -> fieldOrList.isList() ? ITEMS_KEY : fieldOrList.getFieldName())
          .toList();
      return Pair.of(fieldName, node);
    })
        .stream()
        // first node is the original object.
        .skip(1)
        .filter(fieldWithSchema -> filterChildrenOfFoneOneOf(fieldWithSchema.getLeft(),
            fieldWithSchema.getRight(), fieldNamesThatAreOneOfs))
        .toList();
  }

  /**
   * Predicate that checks if a field is a CHILD of a oneOf field. If child of a oneOf, returns false.
   * Otherwise, true. This method as side effects. It assumes that it will be run in order on field
   * names returned in depth-first search preoorder. As it encounters oneOfs it adds them to a
   * collection. It then checks if subsequent field names are prefix matches to the field that are
   * oneOfs.
   *
   * @param fieldName - field to investigate
   * @param schema - schema of field
   * @param oneOfFieldNameAccumulator - collection of fields that are oneOfs
   * @return If child of a oneOf, returns false. Otherwise, true.
   */
  private static boolean filterChildrenOfFoneOneOf(final List<String> fieldName,
                                                   final JsonNode schema,
                                                   final Set<List<String>> oneOfFieldNameAccumulator) {
    if (isOneOfField(schema)) {
      oneOfFieldNameAccumulator.add(fieldName);
      // return early because we know it is a oneOf and therefore cannot be a child of a oneOf.
      return true;
    }

    // leverage that nodes are returned in depth-first search preorder. this means the parent field for
    // the oneOf will be present in the list BEFORE any of its children.
    for (final List<String> oneOfFieldName : oneOfFieldNameAccumulator) {
      final String oneOfFieldNameString = String.join(".", oneOfFieldName);
      final String fieldNameString = String.join(".", fieldName);

      if (fieldNameString.startsWith(oneOfFieldNameString)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isOneOfField(final JsonNode schema) {
    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(schema.fieldNames(), Spliterator.ORDERED), false)
        .noneMatch(name -> name.contains("type"));
  }

  private static Map<StreamDescriptor, AirbyteStream> streamDescriptorToMap(final AirbyteCatalog catalog) {
    return catalog.getStreams()
        .stream()
        .collect(Collectors.toMap(CatalogHelpers::extractStreamDescriptor, s -> s));
  }

  /**
   * Returns difference between two provided catalogs.
   *
   * @param oldCatalog - old catalog
   * @param newCatalog - new catalog
   * @return difference between old and new catalogs
   */
  public static Set<StreamTransform> getCatalogDiff(final AirbyteCatalog oldCatalog,
                                                    final AirbyteCatalog newCatalog,
                                                    final ConfiguredAirbyteCatalog configuredCatalog) {
    final Set<StreamTransform> streamTransforms = new HashSet<>();

    final Map<StreamDescriptor, AirbyteStream> descriptorToStreamOld = streamDescriptorToMap(
        oldCatalog);
    final Map<StreamDescriptor, AirbyteStream> descriptorToStreamNew = streamDescriptorToMap(
        newCatalog);

    Sets.difference(descriptorToStreamOld.keySet(), descriptorToStreamNew.keySet())
        .forEach(descriptor -> streamTransforms.add(
            StreamTransform.createRemoveStreamTransform(descriptor)));
    Sets.difference(descriptorToStreamNew.keySet(), descriptorToStreamOld.keySet())
        .forEach(descriptor -> streamTransforms.add(
            StreamTransform.createAddStreamTransform(descriptor)));
    Sets.intersection(descriptorToStreamOld.keySet(), descriptorToStreamNew.keySet())
        .forEach(descriptor -> {
          final AirbyteStream streamOld = descriptorToStreamOld.get(descriptor);
          final AirbyteStream streamNew = descriptorToStreamNew.get(descriptor);

          final Optional<ConfiguredAirbyteStream> stream = configuredCatalog.getStreams().stream()
              .filter(s -> Objects.equals(s.getStream().getNamespace(), descriptor.getNamespace())
                  && s.getStream().getName().equals(descriptor.getName()))
              .findFirst();

          if (!streamOld.equals(streamNew) && stream.isPresent()) {
            // getStreamDiff only checks for differences in the stream's field name or field type
            // but there are a number of reasons the streams might be different (such as a source-defined
            // primary key or cursor changing). These should not be expressed as "stream updates".
            final UpdateStreamTransform streamTransform = getStreamDiff(streamOld, streamNew, stream);
            if (streamTransform.getFieldTransforms().size() > 0) {
              streamTransforms.add(StreamTransform.createUpdateStreamTransform(descriptor, streamTransform));
            }
          }
        });

    return streamTransforms;
  }

  private static UpdateStreamTransform getStreamDiff(final AirbyteStream streamOld,
                                                     final AirbyteStream streamNew,
                                                     final Optional<ConfiguredAirbyteStream> configuredStream) {
    final Set<FieldTransform> fieldTransforms = new HashSet<>();
    final Map<List<String>, JsonNode> fieldNameToTypeOld = getFullyQualifiedFieldNamesWithTypes(
        streamOld.getJsonSchema())
            .stream()
            .collect(
                HashMap::new,
                CatalogDiffHelpers::collectInHashMap,
                CatalogDiffHelpers::combineAccumulator);
    final Map<List<String>, JsonNode> fieldNameToTypeNew = getFullyQualifiedFieldNamesWithTypes(
        streamNew.getJsonSchema())
            .stream()
            .collect(
                HashMap::new,
                CatalogDiffHelpers::collectInHashMap,
                CatalogDiffHelpers::combineAccumulator);

    Sets.difference(fieldNameToTypeOld.keySet(), fieldNameToTypeNew.keySet())
        .forEach(fieldName -> {
          fieldTransforms.add(FieldTransform.createRemoveFieldTransform(fieldName,
              fieldNameToTypeOld.get(fieldName),
              transformBreaksConnection(configuredStream, fieldName)));
        });
    Sets.difference(fieldNameToTypeNew.keySet(), fieldNameToTypeOld.keySet())
        .forEach(fieldName -> fieldTransforms.add(
            FieldTransform.createAddFieldTransform(fieldName, fieldNameToTypeNew.get(fieldName))));
    Sets.intersection(fieldNameToTypeOld.keySet(), fieldNameToTypeNew.keySet())
        .forEach(fieldName -> {
          final JsonNode oldType = fieldNameToTypeOld.get(fieldName);
          final JsonNode newType = fieldNameToTypeNew.get(fieldName);

          if (!oldType.equals(newType)) {
            fieldTransforms.add(FieldTransform.createUpdateFieldTransform(fieldName,
                new UpdateFieldSchemaTransform(oldType, newType)));
          }
        });

    return new UpdateStreamTransform(fieldTransforms);
  }

  @VisibleForTesting
  static final JsonNode DUPLICATED_SCHEMA = Jsons.jsonNode("Duplicated Schema");

  @VisibleForTesting
  static void collectInHashMap(final Map<List<String>, JsonNode> accumulator,
                               final Pair<List<String>, JsonNode> value) {
    if (accumulator.containsKey(value.getKey())) {
      accumulator.put(value.getKey(), DUPLICATED_SCHEMA);
    } else {
      accumulator.put(value.getKey(), value.getValue());
    }
  }

  @VisibleForTesting
  static void combineAccumulator(final Map<List<String>, JsonNode> accumulatorLeft,
                                 final Map<List<String>, JsonNode> accumulatorRight) {
    accumulatorRight.forEach((key, value) -> {
      if (accumulatorLeft.containsKey(key)) {
        accumulatorLeft.put(key, DUPLICATED_SCHEMA);
      } else {
        accumulatorLeft.put(key, value);
      }
    });
  }

  static boolean transformBreaksConnection(final Optional<ConfiguredAirbyteStream> configuredStream,
                                           final List<String> fieldName) {
    if (configuredStream.isEmpty()) {
      return false;
    }

    final ConfiguredAirbyteStream streamConfig = configuredStream.get();

    final SyncMode syncMode = streamConfig.getSyncMode();
    if (SyncMode.INCREMENTAL == syncMode && streamConfig.getCursorField().equals(fieldName)) {
      return true;
    }

    final DestinationSyncMode destinationSyncMode = streamConfig.getDestinationSyncMode();
    if (DestinationSyncMode.APPEND_DEDUP == destinationSyncMode && streamConfig.getPrimaryKey()
        .contains(fieldName)) {
      return true;
    }
    return false;
  }

}
