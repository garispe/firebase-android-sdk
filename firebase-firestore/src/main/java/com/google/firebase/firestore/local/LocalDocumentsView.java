// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore.local;

import static com.google.firebase.firestore.model.DocumentCollections.emptyDocumentMap;
import static com.google.firebase.firestore.util.Assert.hardAssert;

import android.util.Pair;
import androidx.annotation.VisibleForTesting;
import com.google.firebase.Timestamp;
import com.google.firebase.database.collection.ImmutableSortedMap;
import com.google.firebase.database.collection.StandardComparator;
import com.google.firebase.firestore.core.Query;
import com.google.firebase.firestore.model.Document;
import com.google.firebase.firestore.model.DocumentKey;
import com.google.firebase.firestore.model.FieldIndex.IndexOffset;
import com.google.firebase.firestore.model.MutableDocument;
import com.google.firebase.firestore.model.ResourcePath;
import com.google.firebase.firestore.model.mutation.FieldMask;
import com.google.firebase.firestore.model.mutation.Mutation;
import com.google.firebase.firestore.model.mutation.MutationBatch;
import com.google.firebase.firestore.model.mutation.Overlay;
import com.google.firebase.firestore.model.mutation.PatchMutation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A readonly view of the local state of all documents we're tracking (i.e. we have a cached version
 * in remoteDocumentCache or local mutations for the document). The view is computed by applying the
 * mutations in the MutationQueue to the RemoteDocumentCache.
 */
// TODO: Turn this into the UnifiedDocumentCache / whatever.
class LocalDocumentsView {

  private final RemoteDocumentCache remoteDocumentCache;
  private final MutationQueue mutationQueue;
  private final DocumentOverlayCache documentOverlayCache;
  private final IndexManager indexManager;

  LocalDocumentsView(
      RemoteDocumentCache remoteDocumentCache,
      MutationQueue mutationQueue,
      DocumentOverlayCache documentOverlayCache,
      IndexManager indexManager) {
    this.remoteDocumentCache = remoteDocumentCache;
    this.mutationQueue = mutationQueue;
    this.documentOverlayCache = documentOverlayCache;
    this.indexManager = indexManager;
  }

  @VisibleForTesting
  RemoteDocumentCache getRemoteDocumentCache() {
    return remoteDocumentCache;
  }

  @VisibleForTesting
  MutationQueue getMutationQueue() {
    return mutationQueue;
  }

  @VisibleForTesting
  DocumentOverlayCache getDocumentOverlayCache() {
    return documentOverlayCache;
  }

  @VisibleForTesting
  IndexManager getIndexManager() {
    return indexManager;
  }

  /**
   * Returns the the local view of the document identified by {@code key}.
   *
   * @return Local view of the document or a an invalid document if we don't have any cached state
   *     for it.
   */
  Document getDocument(DocumentKey key) {
    Overlay overlay = documentOverlayCache.getOverlay(key);
    MutableDocument fromOverlay = remoteDocumentCache.get(key);
    if (overlay != null) {
      overlay
          .getMutation()
          .applyToLocalView(fromOverlay, null, overlay.getLargestBatchId(), Timestamp.now());
    }

    return fromOverlay;
  }

  /**
   * Gets the local view of the documents identified by {@code keys}.
   *
   * <p>If we don't have cached state for a document in {@code keys}, a NoDocument will be stored
   * for that key in the resulting set.
   */
  ImmutableSortedMap<DocumentKey, Document> getDocuments(Iterable<DocumentKey> keys) {
    Map<DocumentKey, MutableDocument> docs = remoteDocumentCache.getAll(keys);
    return getLocalViewOfDocuments(docs, new HashSet<>());
  }

  /** Gets the local view of the next {@code count} documents based on their read time. */
  ImmutableSortedMap<DocumentKey, Document> getDocuments(
      String collectionGroup, IndexOffset offset, int count) {
    Map<DocumentKey, MutableDocument> docs =
        remoteDocumentCache.getAll(collectionGroup, offset, count);
    return getLocalViewOfDocuments(docs, new HashSet<>());
  }

  /**
   * Similar to {@link #getDocuments}, but creates the local view from the given {@code baseDocs}
   * without retrieving documents from the local store.
   *
   * @param docs The documents to apply local mutations to get the local views.
   * @param existenceStateChanged The set of document keys whose existence state is changed. This is
   *     useful to determine if some documents overlay needs to be recalculated.
   */
  ImmutableSortedMap<DocumentKey, Document> getLocalViewOfDocuments(
      Map<DocumentKey, MutableDocument> docs, Set<DocumentKey> existenceStateChanged) {
    ImmutableSortedMap<DocumentKey, Document> results = emptyDocumentMap();
    Map<DocumentKey, MutableDocument> recalculateDocuments = new HashMap<>();
    for (Map.Entry<DocumentKey, MutableDocument> entry : docs.entrySet()) {
      Overlay overlay = documentOverlayCache.getOverlay(entry.getKey());
      // Recalculate an overlay if the document's existence state is changed due to a remote
      // event *and* the overlay is a PatchMutation. This is because document existence state
      // can change if some patch mutation's preconditions are met.
      // NOTE: we recalculate when `overlay` is null as well, because there might be a patch
      // mutation whose precondition does not match before the change (hence overlay==null),
      // but would now match.
      if (existenceStateChanged.contains(entry.getKey())
          && (overlay == null || overlay.getMutation() instanceof PatchMutation)) {
        recalculateDocuments.put(entry.getKey(), docs.get(entry.getKey()));
      } else if (overlay != null) {
        overlay
            .getMutation()
            .applyToLocalView(entry.getValue(), null, overlay.getLargestBatchId(), Timestamp.now());
      }
    }

    recalculateAndSaveOverlays(recalculateDocuments);

    for (Map.Entry<DocumentKey, MutableDocument> entry : docs.entrySet()) {
      results = results.insert(entry.getKey(), entry.getValue());
    }
    return results;
  }

  private void recalculateAndSaveOverlays(Map<DocumentKey, MutableDocument> docs) {
    List<MutationBatch> batches =
        mutationQueue.getAllMutationBatchesAffectingDocumentKeys(docs.keySet());

    Map<DocumentKey, FieldMask> masks = new HashMap<>();
    // A reverse lookup map from batch id to the documents within that batch.
    TreeMap<Integer, Set<DocumentKey>> documentsByBatchId = new TreeMap<>();

    // Apply mutations from mutation queue to the documents, collecting batch id and field masks
    // along the way.
    for (MutationBatch batch : batches) {
      for (DocumentKey key : batch.getKeys()) {
        FieldMask mask = masks.containsKey(key) ? masks.get(key) : FieldMask.EMPTY;
        mask = batch.applyToLocalView(docs.get(key), mask);
        masks.put(key, mask);
        int batchId = batch.getBatchId();
        if (!documentsByBatchId.containsKey(batchId)) {
          documentsByBatchId.put(batchId, new HashSet<>());
        }
        documentsByBatchId.get(batchId).add(key);
      }
    }

    Set<DocumentKey> processed = new HashSet<>();
    // Iterate in descending order of batch ids, skip documents that are already saved.
    for (Map.Entry<Integer, Set<DocumentKey>> entry :
        documentsByBatchId.descendingMap().entrySet()) {
      Map<DocumentKey, Mutation> overlays = new HashMap<>();
      for (DocumentKey key : entry.getValue()) {
        if (!processed.contains(key)) {
          overlays.put(key, Mutation.calculateOverlayMutation(docs.get(key), masks.get(key)));
          processed.add(key);
        }
      }
      documentOverlayCache.saveOverlays(entry.getKey(), overlays);
    }
  }

  /**
   * Recalculates overlays by reading the documents from remote document cache first, and save them
   * after they are calculated.
   */
  void recalculateAndSaveOverlays(Set<DocumentKey> documentKeys) {
    Map<DocumentKey, MutableDocument> docs = remoteDocumentCache.getAll(documentKeys);
    recalculateAndSaveOverlays(docs);
  }

  /**
   * Performs a query against the local view of all documents.
   *
   * @param query The query to match documents against.
   * @param offset Read time and key to start scanning by (exclusive).
   */
  ImmutableSortedMap<DocumentKey, Document> getDocumentsMatchingQuery(
      Query query, IndexOffset offset) {
    ResourcePath path = query.getPath();
    if (query.isDocumentQuery()) {
      return getDocumentsMatchingDocumentQuery(path);
    } else if (query.isCollectionGroupQuery()) {
      return getDocumentsMatchingCollectionGroupQuery(query, offset);
    } else {
      return getDocumentsMatchingCollectionQuery(query, offset);
    }
  }

  /** Performs a simple document lookup for the given path. */
  private ImmutableSortedMap<DocumentKey, Document> getDocumentsMatchingDocumentQuery(
      ResourcePath path) {
    ImmutableSortedMap<DocumentKey, Document> result = emptyDocumentMap();
    // Just do a simple document lookup.
    Document doc = getDocument(DocumentKey.fromPath(path));
    if (doc.isFoundDocument()) {
      result = result.insert(doc.getKey(), doc);
    }
    return result;
  }

  private ImmutableSortedMap<DocumentKey, Document> getDocumentsMatchingCollectionGroupQuery(
      Query query, IndexOffset offset) {
    hardAssert(
        query.getPath().isEmpty(),
        "Currently we only support collection group queries at the root.");
    String collectionId = query.getCollectionGroup();
    ImmutableSortedMap<DocumentKey, Document> results = emptyDocumentMap();
    List<ResourcePath> parents = indexManager.getCollectionParents(collectionId);

    // Perform a collection query against each parent that contains the collectionId and
    // aggregate the results.
    for (ResourcePath parent : parents) {
      Query collectionQuery = query.asCollectionQueryAtPath(parent.append(collectionId));
      ImmutableSortedMap<DocumentKey, Document> collectionResults =
          getDocumentsMatchingCollectionQuery(collectionQuery, offset);
      for (Map.Entry<DocumentKey, Document> docEntry : collectionResults) {
        results = results.insert(docEntry.getKey(), docEntry.getValue());
      }
    }
    return results;
  }

  /**
   * Given a collection group, returns the next documents that follow the provided offset, along
   * with an updated offset.
   *
   * <p>This method returns documents in order of read time from the provided offset, followed by
   * documents with mutations in order of batch id from the offset. Since all documents in a batch
   * are returned together, the total number of documents returned can exceed {@code count}.
   *
   * <p>If no documents are found, returns an empty list and an offset with the latest read time in
   * the remote document cache.
   *
   * @param collectionGroup The collection group for the documents.
   * @param offset The offset to index into.
   * @param count The number of documents to return
   * @return A pair containing the next offset and a list of documents that follow the provided
   *     offset.
   */
  public Pair<IndexOffset, ImmutableSortedMap<DocumentKey, Document>> getNextDocumentsAndOffset(
      String collectionGroup, IndexOffset offset, int count) {
    ImmutableSortedMap<DocumentKey, Document> documents =
        getDocuments(collectionGroup, offset, count);

    // TODO(indexing): Store largest batch id on Document class so we can avoid fetching documents
    // a second time.
    ImmutableSortedMap<Integer, List<Document>> batchIdToDocuments =
        getDocumentsBatchIdsMatchingCollectionGroupQuery(collectionGroup, offset);
    IndexOffset newOffset = getNewOffset(documents, offset);

    // Start updating by mutation batch id if under limit.
    while (documents.size() < count && !batchIdToDocuments.isEmpty()) {
      int lowestBatchId = batchIdToDocuments.getMinKey();
      List<Document> documentsInBatch = batchIdToDocuments.get(lowestBatchId);

      for (Document document : documentsInBatch) {
        documents = documents.insert(document.getKey(), document);
      }
      newOffset =
          IndexOffset.create(newOffset.getReadTime(), newOffset.getDocumentKey(), lowestBatchId);
      batchIdToDocuments = batchIdToDocuments.remove(lowestBatchId);
    }

    return new Pair<>(newOffset, documents);
  }

  /**
   * Returns a mapping of batch id to documents that match the provided query sorted by batch id
   * ascending order.
   */
  // TODO(indexing): Support adding local mutations to collection group table.
  public ImmutableSortedMap<Integer, List<Document>>
      getDocumentsBatchIdsMatchingCollectionGroupQuery(String collectionGroup, IndexOffset offset) {
    ImmutableSortedMap<Integer, List<Document>> results =
        ImmutableSortedMap.Builder.emptyMap(StandardComparator.getComparator(Integer.class));
    List<ResourcePath> parents = indexManager.getCollectionParents(collectionGroup);

    // Perform a collection query against each parent that contains the collectionId and
    // aggregate the results.
    for (ResourcePath parent : parents) {
      ResourcePath collectionId = parent.append(collectionGroup);
      Query collectionQuery = Query.atPath(collectionId);
      Map<Document, Integer> documentToBatchIds =
          getDocumentsInOverlayCacheByBatchId(collectionQuery, offset);
      for (Document document : documentToBatchIds.keySet()) {
        int batchId = documentToBatchIds.get(document);
        if (!results.containsKey(batchId)) {
          List<Document> documents = new ArrayList<>();
          documents.add(document);
          results = results.insert(batchId, documents);
        } else {
          results.get(batchId).add(document);
        }
      }
    }
    return results;
  }

  private ImmutableSortedMap<DocumentKey, Document> getDocumentsMatchingCollectionQuery(
      Query query, IndexOffset offset) {
    Map<DocumentKey, MutableDocument> remoteDocuments =
        remoteDocumentCache.getAll(query.getPath(), offset);
    Map<DocumentKey, Overlay> overlays = documentOverlayCache.getOverlays(query.getPath(), -1);

    // As documents might match the query because of their overlay we need to include documents
    // for all overlays in the initial document set.
    for (Map.Entry<DocumentKey, Overlay> entry : overlays.entrySet()) {
      if (!remoteDocuments.containsKey(entry.getKey())) {
        remoteDocuments.put(entry.getKey(), MutableDocument.newInvalidDocument(entry.getKey()));
      }
    }

    // Apply the overlays and match against the query.
    ImmutableSortedMap<DocumentKey, Document> results = emptyDocumentMap();
    for (Map.Entry<DocumentKey, MutableDocument> docEntry : remoteDocuments.entrySet()) {
      Overlay overlay = overlays.get(docEntry.getKey());
      Mutation mutation = overlay != null ? overlay.getMutation() : null;
      if (mutation != null) {
        mutation.applyToLocalView(
            docEntry.getValue(), null, overlay.getLargestBatchId(), Timestamp.now());
      }
      // Finally, insert the documents that still match the query
      if (query.matches(docEntry.getValue())) {
        results = results.insert(docEntry.getKey(), docEntry.getValue());
      }
    }
    return results;
  }

  /**
   * Returns a mapping of documents to their largest batch ids based on the earliest batch id in the
   * provided offset.
   */
  public Map<Document, Integer> getDocumentsInOverlayCacheByBatchId(
      Query query, IndexOffset offset) {
    Map<DocumentKey, MutableDocument> remoteDocuments = new HashMap<>();
    Map<DocumentKey, Overlay> overlays =
        documentOverlayCache.getOverlays(query.getPath(), offset.getLargestBatchId());

    // Apply overlays to empty mutable document map to generate base.
    for (Map.Entry<DocumentKey, Overlay> entry : overlays.entrySet()) {
      remoteDocuments.put(entry.getKey(), MutableDocument.newInvalidDocument(entry.getKey()));
    }

    // Apply the overlays and match against the query.
    Map<Document, Integer> results = new HashMap<>();
    for (Map.Entry<DocumentKey, MutableDocument> docEntry : remoteDocuments.entrySet()) {
      Overlay overlay = overlays.get(docEntry.getKey());
      int batchId = overlay.getLargestBatchId();
      Mutation mutation = overlay.getMutation();
      if (mutation != null) {
        mutation.applyToLocalView(docEntry.getValue(), null, batchId, Timestamp.now());
      }
      // Finally, insert the documents that still match the query.
      if (query.matches(docEntry.getValue())) {
        results.put(docEntry.getValue(), batchId);
      }
    }

    return results;
  }

  /** Returns the offset for the index based on the newly indexed documents. */
  private IndexOffset getNewOffset(
      ImmutableSortedMap<DocumentKey, Document> documents, IndexOffset currentOffset) {
    if (documents.isEmpty()) {
      return IndexOffset.create(remoteDocumentCache.getLatestReadTime());
    } else {
      IndexOffset latestOffset = currentOffset;
      Iterator<Map.Entry<DocumentKey, Document>> it = documents.iterator();
      while (it.hasNext()) {
        IndexOffset newOffset = IndexOffset.fromDocument(it.next().getValue());
        if (newOffset.compareTo(latestOffset) > 0) {
          latestOffset = newOffset;
        }
      }
      return latestOffset;
    }
  }
}
