/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2018-2020 The Feast Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feast.core.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

import com.google.api.client.util.Lists;
import com.google.protobuf.InvalidProtocolBufferException;
import feast.core.dao.FeatureSetRepository;
import feast.core.dao.ProjectRepository;
import feast.core.dao.StoreRepository;
import feast.core.exception.RetrievalException;
import feast.core.model.*;
import feast.core.util.TestUtil;
import feast.proto.core.CoreServiceProto.ApplyFeatureSetResponse;
import feast.proto.core.CoreServiceProto.ApplyFeatureSetResponse.Status;
import feast.proto.core.CoreServiceProto.GetFeatureSetRequest;
import feast.proto.core.CoreServiceProto.GetFeatureSetResponse;
import feast.proto.core.CoreServiceProto.ListFeatureSetsRequest.Filter;
import feast.proto.core.CoreServiceProto.ListFeatureSetsResponse;
import feast.proto.core.CoreServiceProto.ListFeaturesRequest;
import feast.proto.core.CoreServiceProto.ListFeaturesResponse;
import feast.proto.core.CoreServiceProto.ListStoresRequest;
import feast.proto.core.CoreServiceProto.ListStoresResponse;
import feast.proto.core.CoreServiceProto.UpdateStoreRequest;
import feast.proto.core.CoreServiceProto.UpdateStoreResponse;
import feast.proto.core.FeatureSetProto;
import feast.proto.core.FeatureSetProto.EntitySpec;
import feast.proto.core.FeatureSetProto.FeatureSetSpec;
import feast.proto.core.FeatureSetProto.FeatureSpec;
import feast.proto.core.StoreProto;
import feast.proto.core.StoreProto.Store.RedisConfig;
import feast.proto.core.StoreProto.Store.StoreType;
import feast.proto.core.StoreProto.Store.Subscription;
import feast.proto.types.ValueProto.ValueType.Enum;
import java.sql.Date;
import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.tensorflow.metadata.v0.BoolDomain;
import org.tensorflow.metadata.v0.FeaturePresence;
import org.tensorflow.metadata.v0.FeaturePresenceWithinGroup;
import org.tensorflow.metadata.v0.FixedShape;
import org.tensorflow.metadata.v0.FloatDomain;
import org.tensorflow.metadata.v0.ImageDomain;
import org.tensorflow.metadata.v0.IntDomain;
import org.tensorflow.metadata.v0.MIDDomain;
import org.tensorflow.metadata.v0.NaturalLanguageDomain;
import org.tensorflow.metadata.v0.StringDomain;
import org.tensorflow.metadata.v0.StructDomain;
import org.tensorflow.metadata.v0.TimeDomain;
import org.tensorflow.metadata.v0.TimeOfDayDomain;
import org.tensorflow.metadata.v0.URLDomain;
import org.tensorflow.metadata.v0.ValueCount;

public class SpecServiceTest {

  @Mock private FeatureSetRepository featureSetRepository;

  @Mock private StoreRepository storeRepository;

  @Mock private ProjectRepository projectRepository;

  @Rule public final ExpectedException expectedException = ExpectedException.none();

  private SpecService specService;
  private List<FeatureSet> featureSets;
  private List<Feature> features;
  private List<Store> stores;
  private Source defaultSource;

  // TODO: Updates update features in place, so if tests follow the wrong order they might break.
  // Refactor this maybe?
  @Before
  public void setUp() throws InvalidProtocolBufferException {
    initMocks(this);
    defaultSource = TestUtil.defaultSource;

    FeatureSet featureSet1 = newDummyFeatureSet("f1", "project1");
    FeatureSet featureSet2 = newDummyFeatureSet("f2", "project1");

    Map<String, String> featureLabels1 = Map.ofEntries(Map.entry("key1", "val1"));
    Map<String, String> featureLabels2 = Map.ofEntries(Map.entry("key2", "val2"));
    Map<String, String> dummyLabels = Map.ofEntries(Map.entry("key", "value"));

    Feature dummyFeature = TestUtil.CreateFeature("feature", Enum.STRING, dummyLabels);
    Feature f3f1 = TestUtil.CreateFeature("f3f1", Enum.INT64);
    Feature f3f2 = TestUtil.CreateFeature("f3f2", Enum.INT64);
    Entity f3e1 = TestUtil.CreateEntity("f3e1", Enum.STRING);
    FeatureSet featureSet3 =
        TestUtil.CreateFeatureSet("f3", "project1", Arrays.asList(f3e1), Arrays.asList(f3f2, f3f1));

    FeatureSet featureSet4 = newDummyFeatureSet("f4", Project.DEFAULT_NAME);
    Map<String, String> singleFeatureSetLabels =
        new HashMap<>() {
          {
            put("fsLabel1", "fsValue1");
          }
        };
    Map<String, String> duoFeatureSetLabels =
        new HashMap<>() {
          {
            put("fsLabel1", "fsValue1");
            put("fsLabel2", "fsValue2");
          }
        };
    FeatureSet featureSet5 = newDummyFeatureSet("f5", Project.DEFAULT_NAME);
    FeatureSet featureSet6 = newDummyFeatureSet("f6", Project.DEFAULT_NAME);
    FeatureSetSpec featureSetSpec5 = featureSet5.toProto().getSpec().toBuilder().build();
    FeatureSetSpec featureSetSpec6 = featureSet6.toProto().getSpec().toBuilder().build();
    FeatureSetProto.FeatureSet fs5 =
        FeatureSetProto.FeatureSet.newBuilder()
            .setSpec(
                featureSetSpec5
                    .toBuilder()
                    .setSource(defaultSource.toProto())
                    .putAllLabels(singleFeatureSetLabels)
                    .build())
            .build();
    FeatureSetProto.FeatureSet fs6 =
        FeatureSetProto.FeatureSet.newBuilder()
            .setSpec(
                featureSetSpec6
                    .toBuilder()
                    .setSource(defaultSource.toProto())
                    .putAllLabels(duoFeatureSetLabels)
                    .build())
            .build();

    Entity f7e1 = TestUtil.CreateEntity("f7e1", Enum.STRING);
    Entity f9e1 = TestUtil.CreateEntity("f9e1", Enum.STRING);
    Feature f7f1 = TestUtil.CreateFeature("f7f1", Enum.INT64, featureLabels1);
    Feature f8f1 = TestUtil.CreateFeature("f8f1", Enum.INT64, featureLabels2);
    FeatureSet featureSet7 =
        TestUtil.CreateFeatureSet(
            "f7", "project2", Arrays.asList(f7e1), Arrays.asList(f3f1, f3f2, f7f1));
    FeatureSet featureSet8 =
        TestUtil.CreateFeatureSet("f8", "project2", Arrays.asList(f7e1), Arrays.asList(f3f1, f8f1));
    FeatureSet featureSet9 =
        TestUtil.CreateFeatureSet("f9", "default", Arrays.asList(f9e1), Arrays.asList(f3f1, f8f1));
    features = Arrays.asList(dummyFeature, f3f1, f3f2, f7f1, f8f1);

    featureSets =
        Arrays.asList(
            featureSet1,
            featureSet2,
            featureSet3,
            featureSet4,
            FeatureSet.fromProto(fs5),
            FeatureSet.fromProto(fs6),
            featureSet7,
            featureSet8,
            featureSet9);

    when(featureSetRepository.findAll()).thenReturn(featureSets);
    when(featureSetRepository.findAllByOrderByNameAsc()).thenReturn(featureSets);
    when(featureSetRepository.findFeatureSetByNameAndProject_Name("f1", "project1"))
        .thenReturn(featureSets.get(0));
    when(featureSetRepository.findFeatureSetByNameAndProject_Name("f2", "project1"))
        .thenReturn(featureSets.get(1));
    when(featureSetRepository.findAllByNameLikeAndProject_NameOrderByNameAsc("f1", "project1"))
        .thenReturn(featureSets.subList(0, 1));
    when(featureSetRepository.findAllByNameLikeAndProject_NameOrderByNameAsc("%", "default"))
        .thenReturn(featureSets.subList(8, 9));
    when(featureSetRepository.findAllByNameLikeAndProject_NameOrderByNameAsc("%", "project2"))
        .thenReturn(featureSets.subList(6, 8));
    when(featureSetRepository.findAllByNameLikeAndProject_NameOrderByNameAsc("asd", "project1"))
        .thenReturn(Lists.newArrayList());
    when(featureSetRepository.findAllByNameLikeAndProject_NameOrderByNameAsc("f%", "project1"))
        .thenReturn(featureSets);
    when(featureSetRepository.findAllByNameLikeAndProject_NameLikeOrderByNameAsc("%", "%"))
        .thenReturn(featureSets);

    when(projectRepository.findAllByArchivedIsFalse())
        .thenReturn(Collections.singletonList(new Project("project1")));
    when(projectRepository.findById("project1")).thenReturn(Optional.of(new Project("project1")));
    Project archivedProject = new Project("archivedproject");
    archivedProject.setArchived(true);
    when(projectRepository.findById(archivedProject.getName()))
        .thenReturn(Optional.of(archivedProject));

    Store store1 = newDummyStore("SERVING");
    Store store2 = newDummyStore("WAREHOUSE");
    stores = Arrays.asList(store1, store2);
    when(storeRepository.findAll()).thenReturn(stores);
    when(storeRepository.findById("SERVING")).thenReturn(Optional.of(store1));
    when(storeRepository.findById("NOTFOUND")).thenReturn(Optional.empty());

    specService =
        new SpecService(featureSetRepository, storeRepository, projectRepository, defaultSource);
  }

  @Test
  public void shouldGetAllFeatureSetsIfOnlyWildcardsProvided()
      throws InvalidProtocolBufferException {
    ListFeatureSetsResponse actual =
        specService.listFeatureSets(
            Filter.newBuilder().setFeatureSetName("*").setProject("*").build());
    List<FeatureSetProto.FeatureSet> list = new ArrayList<>();
    for (FeatureSet featureSet : featureSets) {
      FeatureSetProto.FeatureSet toProto = featureSet.toProto();
      list.add(toProto);
    }
    ListFeatureSetsResponse expected =
        ListFeatureSetsResponse.newBuilder().addAllFeatureSets(list).build();
    assertThat(actual, equalTo(expected));
  }

  @Test
  public void shouldGetAllFeatureSetsMatchingNameWithWildcardSearch()
      throws InvalidProtocolBufferException {
    ListFeatureSetsResponse actual =
        specService.listFeatureSets(
            Filter.newBuilder().setProject("project1").setFeatureSetName("f*").build());
    List<FeatureSet> expectedFeatureSets =
        featureSets.stream()
            .filter(fs -> fs.getName().startsWith("f"))
            .collect(Collectors.toList());
    List<FeatureSetProto.FeatureSet> list = new ArrayList<>();
    for (FeatureSet expectedFeatureSet : expectedFeatureSets) {
      FeatureSetProto.FeatureSet toProto = expectedFeatureSet.toProto();
      list.add(toProto);
    }
    ListFeatureSetsResponse expected =
        ListFeatureSetsResponse.newBuilder().addAllFeatureSets(list).build();
    assertThat(actual, equalTo(expected));
  }

  @Test
  public void shouldGetFeatureSetsByNameAndProject() throws InvalidProtocolBufferException {
    ListFeatureSetsResponse actual =
        specService.listFeatureSets(
            Filter.newBuilder().setProject("project1").setFeatureSetName("f1").build());
    List<FeatureSet> expectedFeatureSets =
        featureSets.stream().filter(fs -> fs.getName().equals("f1")).collect(Collectors.toList());
    List<FeatureSetProto.FeatureSet> list = new ArrayList<>();
    for (FeatureSet expectedFeatureSet : expectedFeatureSets) {
      FeatureSetProto.FeatureSet toProto = expectedFeatureSet.toProto();
      list.add(toProto);
    }
    ListFeatureSetsResponse expected =
        ListFeatureSetsResponse.newBuilder().addAllFeatureSets(list).build();
    assertThat(actual, equalTo(expected));
  }

  @Test
  public void shouldThrowExceptionGivenMissingFeatureSetName()
      throws InvalidProtocolBufferException {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("No feature set name provided");
    specService.getFeatureSet(GetFeatureSetRequest.newBuilder().build());
  }

  @Test
  public void shouldThrowExceptionGivenMissingFeatureSet() throws InvalidProtocolBufferException {
    expectedException.expect(RetrievalException.class);
    expectedException.expectMessage("Feature set with name \"f1000\" could not be found.");
    specService.getFeatureSet(
        GetFeatureSetRequest.newBuilder().setName("f1000").setProject("project1").build());
  }

  @Test
  public void shouldReturnAllStoresIfNoNameProvided() throws InvalidProtocolBufferException {
    ListStoresResponse actual =
        specService.listStores(ListStoresRequest.Filter.newBuilder().build());
    ListStoresResponse.Builder expected = ListStoresResponse.newBuilder();
    for (Store expectedStore : stores) {
      expected.addStore(expectedStore.toProto());
    }
    assertThat(actual, equalTo(expected.build()));
  }

  @Test
  public void shouldReturnStoreWithName() throws InvalidProtocolBufferException {
    ListStoresResponse actual =
        specService.listStores(ListStoresRequest.Filter.newBuilder().setName("SERVING").build());
    List<Store> expectedStores =
        stores.stream().filter(s -> s.getName().equals("SERVING")).collect(Collectors.toList());
    ListStoresResponse.Builder expected = ListStoresResponse.newBuilder();
    for (Store expectedStore : expectedStores) {
      expected.addStore(expectedStore.toProto());
    }
    assertThat(actual, equalTo(expected.build()));
  }

  @Test
  public void shouldThrowRetrievalExceptionIfNoStoresFoundWithName() {
    expectedException.expect(RetrievalException.class);
    expectedException.expectMessage("Store with name 'NOTFOUND' not found");
    specService.listStores(ListStoresRequest.Filter.newBuilder().setName("NOTFOUND").build());
  }

  @Test
  public void applyFeatureSetShouldReturnFeatureSetIfFeatureSetHasNotChanged()
      throws InvalidProtocolBufferException {
    FeatureSetSpec incomingFeatureSetSpec =
        featureSets.get(0).toProto().getSpec().toBuilder().build();

    ApplyFeatureSetResponse applyFeatureSetResponse =
        specService.applyFeatureSet(
            FeatureSetProto.FeatureSet.newBuilder().setSpec(incomingFeatureSetSpec).build());

    verify(featureSetRepository, times(0)).save(ArgumentMatchers.any(FeatureSet.class));
    assertThat(applyFeatureSetResponse.getStatus(), equalTo(Status.NO_CHANGE));
    assertThat(applyFeatureSetResponse.getFeatureSet(), equalTo(featureSets.get(0).toProto()));
  }

  @Test
  public void applyFeatureSetShouldApplyFeatureSetIfNotExists()
      throws InvalidProtocolBufferException {
    when(featureSetRepository.findFeatureSetByNameAndProject_Name("f2", "project1"))
        .thenReturn(null);

    FeatureSetProto.FeatureSet incomingFeatureSet = newDummyFeatureSet("f2", "project1").toProto();

    FeatureSetProto.FeatureSetSpec incomingFeatureSetSpec =
        incomingFeatureSet.getSpec().toBuilder().build();

    ApplyFeatureSetResponse applyFeatureSetResponse =
        specService.applyFeatureSet(
            FeatureSetProto.FeatureSet.newBuilder().setSpec(incomingFeatureSet.getSpec()).build());
    verify(projectRepository).saveAndFlush(ArgumentMatchers.any(Project.class));

    FeatureSetProto.FeatureSet expected =
        FeatureSetProto.FeatureSet.newBuilder()
            .setSpec(incomingFeatureSetSpec.toBuilder().setSource(defaultSource.toProto()).build())
            .build();
    assertThat(applyFeatureSetResponse.getStatus(), equalTo(Status.CREATED));
    assertThat(applyFeatureSetResponse.getFeatureSet().getSpec(), equalTo(expected.getSpec()));
    assertThat(applyFeatureSetResponse.getFeatureSet().getSpec().getVersion(), equalTo(1));
  }

  @Test
  public void applyFeatureSetShouldUpdateAndSaveFeatureSetIfAlreadyExists()
      throws InvalidProtocolBufferException {
    FeatureSetProto.FeatureSet incomingFeatureSet = featureSets.get(0).toProto();
    incomingFeatureSet =
        incomingFeatureSet
            .toBuilder()
            .setMeta(incomingFeatureSet.getMeta())
            .setSpec(
                incomingFeatureSet
                    .getSpec()
                    .toBuilder()
                    .addFeatures(
                        FeatureSpec.newBuilder().setName("feature2").setValueType(Enum.STRING))
                    .build())
            .build();

    FeatureSetProto.FeatureSet expected =
        incomingFeatureSet
            .toBuilder()
            .setMeta(incomingFeatureSet.getMeta().toBuilder().build())
            .setSpec(
                incomingFeatureSet
                    .getSpec()
                    .toBuilder()
                    .setVersion(2)
                    .setSource(defaultSource.toProto())
                    .build())
            .build();

    ApplyFeatureSetResponse applyFeatureSetResponse =
        specService.applyFeatureSet(incomingFeatureSet);
    verify(projectRepository).saveAndFlush(ArgumentMatchers.any(Project.class));
    assertThat(applyFeatureSetResponse.getStatus(), equalTo(Status.UPDATED));
    assertEquals(
        FeatureSet.fromProto(applyFeatureSetResponse.getFeatureSet()),
        FeatureSet.fromProto(expected));

    assertThat(applyFeatureSetResponse.getFeatureSet().getSpec().getVersion(), equalTo(2));
  }

  @Test
  public void applyFeatureSetShouldNotCreateFeatureSetIfFieldsUnordered()
      throws InvalidProtocolBufferException {

    FeatureSet featureSet = featureSets.get(1);
    List<Feature> features = Lists.newArrayList(featureSet.getFeatures());
    Collections.shuffle(features);
    featureSet.setFeatures(Set.copyOf(features));
    FeatureSetProto.FeatureSet incomingFeatureSet = featureSet.toProto();

    ApplyFeatureSetResponse applyFeatureSetResponse =
        specService.applyFeatureSet(incomingFeatureSet);
    assertThat(applyFeatureSetResponse.getStatus(), equalTo(Status.NO_CHANGE));
    assertThat(
        applyFeatureSetResponse.getFeatureSet().getSpec().getMaxAge(),
        equalTo(incomingFeatureSet.getSpec().getMaxAge()));
    assertThat(
        applyFeatureSetResponse.getFeatureSet().getSpec().getEntities(0),
        equalTo(incomingFeatureSet.getSpec().getEntities(0)));
    assertThat(
        applyFeatureSetResponse.getFeatureSet().getSpec().getName(),
        equalTo(incomingFeatureSet.getSpec().getName()));
  }

  @Test
  public void applyFeatureSetShouldAcceptPresenceShapeAndDomainConstraints()
      throws InvalidProtocolBufferException {
    List<EntitySpec> entitySpecs = new ArrayList<>();
    entitySpecs.add(EntitySpec.newBuilder().setName("entity1").setValueType(Enum.INT64).build());
    entitySpecs.add(EntitySpec.newBuilder().setName("entity2").setValueType(Enum.INT64).build());
    entitySpecs.add(EntitySpec.newBuilder().setName("entity3").setValueType(Enum.FLOAT).build());
    entitySpecs.add(EntitySpec.newBuilder().setName("entity4").setValueType(Enum.STRING).build());
    entitySpecs.add(EntitySpec.newBuilder().setName("entity5").setValueType(Enum.BOOL).build());

    List<FeatureSpec> featureSpecs = new ArrayList<>();
    featureSpecs.add(
        FeatureSpec.newBuilder()
            .setName("feature1")
            .setValueType(Enum.INT64)
            .setPresence(FeaturePresence.getDefaultInstance())
            .setShape(FixedShape.getDefaultInstance())
            .setDomain("mydomain")
            .build());
    featureSpecs.add(
        FeatureSpec.newBuilder()
            .setName("feature2")
            .setValueType(Enum.INT64)
            .setGroupPresence(FeaturePresenceWithinGroup.getDefaultInstance())
            .setValueCount(ValueCount.getDefaultInstance())
            .setIntDomain(IntDomain.getDefaultInstance())
            .build());
    featureSpecs.add(
        FeatureSpec.newBuilder()
            .setName("feature3")
            .setValueType(Enum.FLOAT)
            .setPresence(FeaturePresence.getDefaultInstance())
            .setValueCount(ValueCount.getDefaultInstance())
            .setFloatDomain(FloatDomain.getDefaultInstance())
            .build());
    featureSpecs.add(
        FeatureSpec.newBuilder()
            .setName("feature4")
            .setValueType(Enum.STRING)
            .setPresence(FeaturePresence.getDefaultInstance())
            .setValueCount(ValueCount.getDefaultInstance())
            .setStringDomain(StringDomain.getDefaultInstance())
            .build());
    featureSpecs.add(
        FeatureSpec.newBuilder()
            .setName("feature5")
            .setValueType(Enum.BOOL)
            .setPresence(FeaturePresence.getDefaultInstance())
            .setValueCount(ValueCount.getDefaultInstance())
            .setBoolDomain(BoolDomain.getDefaultInstance())
            .build());

    FeatureSetSpec featureSetSpec =
        FeatureSetSpec.newBuilder()
            .setProject("project1")
            .setName("featureSetWithConstraints")
            .addAllEntities(entitySpecs)
            .addAllFeatures(featureSpecs)
            .build();
    FeatureSetProto.FeatureSet featureSet =
        FeatureSetProto.FeatureSet.newBuilder().setSpec(featureSetSpec).build();

    ApplyFeatureSetResponse applyFeatureSetResponse = specService.applyFeatureSet(featureSet);
    FeatureSetSpec appliedFeatureSetSpec = applyFeatureSetResponse.getFeatureSet().getSpec();

    // appliedEntitySpecs needs to be sorted because the list returned by specService may not
    // follow the order in the request
    List<EntitySpec> appliedEntitySpecs = new ArrayList<>(appliedFeatureSetSpec.getEntitiesList());
    appliedEntitySpecs.sort(Comparator.comparing(EntitySpec::getName));

    // appliedFeatureSpecs needs to be sorted because the list returned by specService may not
    // follow the order in the request
    List<FeatureSpec> appliedFeatureSpecs =
        new ArrayList<>(appliedFeatureSetSpec.getFeaturesList());
    appliedFeatureSpecs.sort(Comparator.comparing(FeatureSpec::getName));

    assertEquals(appliedEntitySpecs, entitySpecs);
    assertEquals(appliedFeatureSpecs, featureSpecs);
  }

  @Test
  public void applyFeatureSetShouldUpdateFeatureSetWhenConstraintsAreUpdated()
      throws InvalidProtocolBufferException {

    // Map of constraint field name -> value, e.g. "shape" -> FixedShape object.
    // If any of these fields are updated, SpecService should update the FeatureSet.
    Map<String, Object> contraintUpdates = new HashMap<>();
    contraintUpdates.put("presence", FeaturePresence.newBuilder().setMinFraction(0.5).build());
    contraintUpdates.put(
        "group_presence", FeaturePresenceWithinGroup.newBuilder().setRequired(true).build());
    contraintUpdates.put("shape", FixedShape.getDefaultInstance());
    contraintUpdates.put("value_count", ValueCount.newBuilder().setMin(2).build());
    contraintUpdates.put("domain", "new_domain");
    contraintUpdates.put("int_domain", IntDomain.newBuilder().setMax(100).build());
    contraintUpdates.put("float_domain", FloatDomain.newBuilder().setMin(-0.5f).build());
    contraintUpdates.put("string_domain", StringDomain.newBuilder().addValue("string1").build());
    contraintUpdates.put("bool_domain", BoolDomain.newBuilder().setFalseValue("falsy").build());
    contraintUpdates.put("struct_domain", StructDomain.getDefaultInstance());
    contraintUpdates.put("natural_language_domain", NaturalLanguageDomain.getDefaultInstance());
    contraintUpdates.put("image_domain", ImageDomain.getDefaultInstance());
    contraintUpdates.put("mid_domain", MIDDomain.getDefaultInstance());
    contraintUpdates.put("url_domain", URLDomain.getDefaultInstance());
    contraintUpdates.put(
        "time_domain", TimeDomain.newBuilder().setStringFormat("string_format").build());
    contraintUpdates.put("time_of_day_domain", TimeOfDayDomain.getDefaultInstance());

    for (Entry<String, Object> constraint : contraintUpdates.entrySet()) {
      FeatureSet featureSet = newDummyFeatureSet("constraints", "project1");
      FeatureSetProto.FeatureSet existingFeatureSet = featureSet.toProto();
      when(featureSetRepository.findFeatureSetByNameAndProject_Name("constraints", "project1"))
          .thenReturn(featureSet);
      String name = constraint.getKey();
      Object value = constraint.getValue();
      FeatureSpec newFeatureSpec =
          existingFeatureSet
              .getSpec()
              .getFeatures(0)
              .toBuilder()
              .setField(FeatureSpec.getDescriptor().findFieldByName(name), value)
              .build();
      FeatureSetSpec newFeatureSetSpec =
          existingFeatureSet.getSpec().toBuilder().setFeatures(0, newFeatureSpec).build();
      FeatureSetProto.FeatureSet newFeatureSet =
          existingFeatureSet.toBuilder().setSpec(newFeatureSetSpec).build();

      ApplyFeatureSetResponse response = specService.applyFeatureSet(newFeatureSet);

      assertEquals(
          "Response should have CREATED status when field '" + name + "' is updated",
          Status.UPDATED,
          response.getStatus());
      assertEquals(
          "Feature should have field '" + name + "' set correctly",
          constraint.getValue(),
          response
              .getFeatureSet()
              .getSpec()
              .getFeatures(0)
              .getField(FeatureSpec.getDescriptor().findFieldByName(name)));
    }
  }

  @Test
  public void applyFeatureSetShouldCreateProjectWhenNotAlreadyExists()
      throws InvalidProtocolBufferException {
    Feature f3f1 = TestUtil.CreateFeature("f3f1", Enum.INT64);
    Feature f3f2 = TestUtil.CreateFeature("f3f2", Enum.INT64);
    Entity f3e1 = TestUtil.CreateEntity("f3e1", Enum.STRING);
    FeatureSetProto.FeatureSet incomingFeatureSet =
        TestUtil.CreateFeatureSet("f3", "project", Arrays.asList(f3e1), Arrays.asList(f3f2, f3f1))
            .toProto();

    ApplyFeatureSetResponse applyFeatureSetResponse =
        specService.applyFeatureSet(incomingFeatureSet);
    assertThat(applyFeatureSetResponse.getStatus(), equalTo(Status.CREATED));
    assertThat(
        applyFeatureSetResponse.getFeatureSet().getSpec().getProject(),
        equalTo(incomingFeatureSet.getSpec().getProject()));
  }

  @Test
  public void applyFeatureSetShouldUsedDefaultProjectIfUnspecified()
      throws InvalidProtocolBufferException {
    Feature f3f1 = TestUtil.CreateFeature("f3f1", Enum.INT64);
    Feature f3f2 = TestUtil.CreateFeature("f3f2", Enum.INT64);
    Entity f3e1 = TestUtil.CreateEntity("f3e1", Enum.STRING);

    // In protov3, unspecified project defaults to ""
    FeatureSetProto.FeatureSet incomingFeatureSet =
        TestUtil.CreateFeatureSet("f3", "", Arrays.asList(f3e1), Arrays.asList(f3f2, f3f1))
            .toProto();
    ApplyFeatureSetResponse applyFeatureSetResponse =
        specService.applyFeatureSet(incomingFeatureSet);
    assertThat(applyFeatureSetResponse.getStatus(), equalTo(Status.CREATED));

    assertThat(
        applyFeatureSetResponse.getFeatureSet().getSpec().getProject(),
        equalTo(Project.DEFAULT_NAME));
  }

  @Test
  public void applyFeatureSetShouldFailWhenProjectIsArchived()
      throws InvalidProtocolBufferException {
    Feature f3f1 = TestUtil.CreateFeature("f3f1", Enum.INT64);
    Feature f3f2 = TestUtil.CreateFeature("f3f2", Enum.INT64);
    Entity f3e1 = TestUtil.CreateEntity("f3e1", Enum.STRING);
    FeatureSetProto.FeatureSet incomingFeatureSet =
        TestUtil.CreateFeatureSet(
                "f3", "archivedproject", Arrays.asList(f3e1), Arrays.asList(f3f2, f3f1))
            .toProto();

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Project is archived");
    specService.applyFeatureSet(incomingFeatureSet);
  }

  @Test
  public void applyFeatureSetShouldAcceptFeatureLabels() throws InvalidProtocolBufferException {

    Map<String, String> featureLabels0 =
        new HashMap<>() {
          {
            put("label1", "feast1");
          }
        };

    Map<String, String> featureLabels1 =
        new HashMap<>() {
          {
            put("label1", "feast1");
            put("label2", "feast2");
          }
        };

    List<Map<String, String>> featureLabels = new ArrayList<>();
    featureLabels.add(featureLabels0);
    featureLabels.add(featureLabels1);

    List<FeatureSpec> featureSpecs = new ArrayList<>();
    featureSpecs.add(
        FeatureSpec.newBuilder()
            .setName("feature1")
            .setValueType(Enum.INT64)
            .putAllLabels(featureLabels.get(0))
            .build());
    featureSpecs.add(
        FeatureSpec.newBuilder()
            .setName("feature2")
            .setValueType(Enum.INT64)
            .putAllLabels(featureLabels.get(1))
            .build());

    FeatureSetSpec featureSetSpec =
        FeatureSetSpec.newBuilder()
            .setProject("project1")
            .setName("featureSetWithConstraints")
            .addAllFeatures(featureSpecs)
            .build();
    FeatureSetProto.FeatureSet featureSet =
        FeatureSetProto.FeatureSet.newBuilder().setSpec(featureSetSpec).build();

    ApplyFeatureSetResponse applyFeatureSetResponse = specService.applyFeatureSet(featureSet);
    FeatureSetSpec appliedFeatureSetSpec = applyFeatureSetResponse.getFeatureSet().getSpec();

    // appliedFeatureSpecs needs to be sorted because the list returned by specService may not
    // follow the order in the request
    List<FeatureSpec> appliedFeatureSpecs =
        new ArrayList<>(appliedFeatureSetSpec.getFeaturesList());
    appliedFeatureSpecs.sort(Comparator.comparing(FeatureSpec::getName));

    var appliedFeatureSpecsLabels =
        appliedFeatureSpecs.stream().map(e -> e.getLabelsMap()).collect(Collectors.toList());
    assertEquals(appliedFeatureSpecsLabels, featureLabels);
  }

  @Test
  public void applyFeatureSetShouldAcceptFeatureSetLabels() throws InvalidProtocolBufferException {
    Map<String, String> featureSetLabels =
        new HashMap<>() {
          {
            put("description", "My precious feature set");
          }
        };

    FeatureSetSpec featureSetSpec =
        FeatureSetSpec.newBuilder()
            .setProject("project1")
            .setName("preciousFeatureSet")
            .putAllLabels(featureSetLabels)
            .build();
    FeatureSetProto.FeatureSet featureSet =
        FeatureSetProto.FeatureSet.newBuilder().setSpec(featureSetSpec).build();

    ApplyFeatureSetResponse applyFeatureSetResponse = specService.applyFeatureSet(featureSet);
    FeatureSetSpec appliedFeatureSetSpec = applyFeatureSetResponse.getFeatureSet().getSpec();

    var appliedLabels = appliedFeatureSetSpec.getLabelsMap();

    assertEquals(featureSetLabels, appliedLabels);
  }

  @Test
  public void shouldFilterFeaturesByEntitiesAndLabels() throws InvalidProtocolBufferException {
    // Case 1: Only filter by entities
    List<String> entities = new ArrayList<>();
    String currentProject = "project2";

    entities.add("f7e1");
    ListFeaturesResponse actual1 =
        specService.listFeatures(
            ListFeaturesRequest.Filter.newBuilder()
                .setProject(currentProject)
                .addAllEntities(entities)
                .build());
    Map<String, FeatureSpec> expectedMap1 =
        Map.ofEntries(
            Map.entry(
                currentProject
                    + "/"
                    + featureSets.get(6).getName()
                    + ":"
                    + features.get(1).getName(),
                features.get(1).toProto()),
            Map.entry(
                currentProject
                    + "/"
                    + featureSets.get(6).getName()
                    + ":"
                    + features.get(2).getName(),
                features.get(2).toProto()),
            Map.entry(
                currentProject
                    + "/"
                    + featureSets.get(6).getName()
                    + ":"
                    + features.get(3).getName(),
                features.get(3).toProto()),
            Map.entry(
                currentProject
                    + "/"
                    + featureSets.get(7).getName()
                    + ":"
                    + features.get(1).getName(),
                features.get(1).toProto()),
            Map.entry(
                currentProject
                    + "/"
                    + featureSets.get(7).getName()
                    + ":"
                    + features.get(4).getName(),
                features.get(4).toProto()));
    ListFeaturesResponse expected1 =
        ListFeaturesResponse.newBuilder().putAllFeatures(expectedMap1).build();

    // Case 2: Filter by entities and labels
    Map<String, String> featureLabels1 = Map.ofEntries(Map.entry("key1", "val1"));
    Map<String, String> featureLabels2 = Map.ofEntries(Map.entry("key2", "val2"));
    ListFeaturesResponse actual2 =
        specService.listFeatures(
            ListFeaturesRequest.Filter.newBuilder()
                .setProject(currentProject)
                .addAllEntities(entities)
                .putAllLabels(featureLabels1)
                .build());
    Map<String, FeatureSpec> expectedMap2 =
        Map.ofEntries(
            Map.entry(
                currentProject
                    + "/"
                    + featureSets.get(6).getName()
                    + ":"
                    + features.get(3).getName(),
                features.get(3).toProto()));
    ListFeaturesResponse expected2 =
        ListFeaturesResponse.newBuilder().putAllFeatures(expectedMap2).build();

    // Case 3: Filter by labels
    ListFeaturesResponse actual3 =
        specService.listFeatures(
            ListFeaturesRequest.Filter.newBuilder()
                .setProject(currentProject)
                .putAllLabels(featureLabels2)
                .build());
    Map<String, FeatureSpec> expectedMap3 =
        Map.ofEntries(
            Map.entry(
                currentProject
                    + "/"
                    + featureSets.get(7).getName()
                    + ":"
                    + features.get(4).getName(),
                features.get(4).toProto()));
    ListFeaturesResponse expected3 =
        ListFeaturesResponse.newBuilder().putAllFeatures(expectedMap3).build();

    // Case 4: Filter by nothing, except project
    ListFeaturesResponse actual4 =
        specService.listFeatures(
            ListFeaturesRequest.Filter.newBuilder().setProject(currentProject).build());
    Map<String, FeatureSpec> expectedMap4 =
        Map.ofEntries(
            Map.entry(
                currentProject
                    + "/"
                    + featureSets.get(6).getName()
                    + ":"
                    + features.get(1).getName(),
                features.get(1).toProto()),
            Map.entry(
                currentProject
                    + "/"
                    + featureSets.get(6).getName()
                    + ":"
                    + features.get(2).getName(),
                features.get(2).toProto()),
            Map.entry(
                currentProject
                    + "/"
                    + featureSets.get(6).getName()
                    + ":"
                    + features.get(3).getName(),
                features.get(3).toProto()),
            Map.entry(
                currentProject
                    + "/"
                    + featureSets.get(7).getName()
                    + ":"
                    + features.get(1).getName(),
                features.get(1).toProto()),
            Map.entry(
                currentProject
                    + "/"
                    + featureSets.get(7).getName()
                    + ":"
                    + features.get(4).getName(),
                features.get(4).toProto()));
    ListFeaturesResponse expected4 =
        ListFeaturesResponse.newBuilder().putAllFeatures(expectedMap4).build();

    // Case 5: Filter by nothing; will use default project
    currentProject = "default";
    ListFeaturesResponse actual5 =
        specService.listFeatures(ListFeaturesRequest.Filter.newBuilder().build());
    Map<String, FeatureSpec> expectedMap5 =
        Map.ofEntries(
            Map.entry(
                currentProject
                    + "/"
                    + featureSets.get(8).getName()
                    + ":"
                    + features.get(1).getName(),
                features.get(1).toProto()),
            Map.entry(
                currentProject
                    + "/"
                    + featureSets.get(8).getName()
                    + ":"
                    + features.get(4).getName(),
                features.get(4).toProto()));
    ListFeaturesResponse expected5 =
        ListFeaturesResponse.newBuilder().putAllFeatures(expectedMap5).build();

    assertThat(actual1, equalTo(expected1));
    assertThat(actual2, equalTo(expected2));
    assertThat(actual3, equalTo(expected3));
    assertThat(actual4, equalTo(expected4));
    assertThat(actual5, equalTo(expected5));
  }

  public void shouldFilterByFeatureSetLabels() throws InvalidProtocolBufferException {
    List<FeatureSetProto.FeatureSet> list = new ArrayList<>();
    ListFeatureSetsResponse actual1 =
        specService.listFeatureSets(
            Filter.newBuilder()
                .setFeatureSetName("*")
                .setProject("*")
                .putLabels("fsLabel2", "fsValue2")
                .build());
    list.add(featureSets.get(5).toProto());
    ListFeatureSetsResponse expected1 =
        ListFeatureSetsResponse.newBuilder().addAllFeatureSets(list).build();

    ListFeatureSetsResponse actual2 =
        specService.listFeatureSets(
            Filter.newBuilder()
                .setFeatureSetName("*")
                .setProject("*")
                .putLabels("fsLabel1", "fsValue1")
                .build());
    list.add(0, featureSets.get(4).toProto());
    ListFeatureSetsResponse expected2 =
        ListFeatureSetsResponse.newBuilder().addAllFeatureSets(list).build();

    assertThat(actual1, equalTo(expected1));
    assertThat(actual2, equalTo(expected2));
  }

  @Test
  public void shouldUpdateStoreIfConfigChanges() throws InvalidProtocolBufferException {
    when(storeRepository.findById("SERVING")).thenReturn(Optional.of(stores.get(0)));
    StoreProto.Store newStore =
        StoreProto.Store.newBuilder()
            .setName("SERVING")
            .setType(StoreType.REDIS)
            .setRedisConfig(RedisConfig.newBuilder())
            .addSubscriptions(Subscription.newBuilder().setProject("project1").setName("a"))
            .build();
    UpdateStoreResponse actual =
        specService.updateStore(UpdateStoreRequest.newBuilder().setStore(newStore).build());
    UpdateStoreResponse expected =
        UpdateStoreResponse.newBuilder()
            .setStore(newStore)
            .setStatus(UpdateStoreResponse.Status.UPDATED)
            .build();
    ArgumentCaptor<Store> argumentCaptor = ArgumentCaptor.forClass(Store.class);
    verify(storeRepository, times(1)).save(argumentCaptor.capture());
    assertThat(argumentCaptor.getValue().toProto(), equalTo(newStore));
    assertThat(actual, equalTo(expected));
  }

  @Test
  public void shouldDoNothingIfNoChange() throws InvalidProtocolBufferException {
    when(storeRepository.findById("SERVING")).thenReturn(Optional.of(stores.get(0)));
    UpdateStoreResponse actual =
        specService.updateStore(
            UpdateStoreRequest.newBuilder().setStore(stores.get(0).toProto()).build());
    UpdateStoreResponse expected =
        UpdateStoreResponse.newBuilder()
            .setStore(stores.get(0).toProto())
            .setStatus(UpdateStoreResponse.Status.NO_CHANGE)
            .build();
    verify(storeRepository, times(0)).save(ArgumentMatchers.any());
    assertThat(actual, equalTo(expected));
  }

  @Test
  public void getOrListFeatureSetShouldUseDefaultProjectIfProjectUnspecified()
      throws InvalidProtocolBufferException {
    when(featureSetRepository.findFeatureSetByNameAndProject_Name("f4", Project.DEFAULT_NAME))
        .thenReturn(featureSets.get(3));
    FeatureSet expected = featureSets.get(3);
    // check getFeatureSet()
    GetFeatureSetResponse getResponse =
        specService.getFeatureSet(GetFeatureSetRequest.newBuilder().setName("f4").build());
    assertThat(getResponse.getFeatureSet(), equalTo(expected.toProto()));

    // check listFeatureSets()
    ListFeatureSetsResponse listResponse =
        specService.listFeatureSets(Filter.newBuilder().setFeatureSetName("f4").build());
    assertThat(listResponse.getFeatureSetsList(), equalTo(Arrays.asList(expected.toProto())));
  }

  private FeatureSet newDummyFeatureSet(String name, String project) {
    FeatureSpec f1 =
        FeatureSpec.newBuilder()
            .setName("feature")
            .setValueType(Enum.STRING)
            .putLabels("key", "value")
            .build();
    Feature feature = Feature.fromProto(f1);
    Entity entity = TestUtil.CreateEntity("entity", Enum.STRING);

    FeatureSet fs =
        TestUtil.CreateFeatureSet(name, project, Arrays.asList(entity), Arrays.asList(feature));
    fs.setCreated(Date.from(Instant.ofEpochSecond(10L)));
    return fs;
  }

  private Store newDummyStore(String name) {
    // Add type to this method when we enable filtering by type
    Store store = new Store();
    store.setName(name);
    store.setType(StoreType.REDIS.toString());
    store.setSubscriptions("*:*");
    store.setConfig(RedisConfig.newBuilder().setPort(6379).build().toByteArray());
    return store;
  }
}
