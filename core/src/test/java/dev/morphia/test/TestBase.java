package dev.morphia.test;

import com.antwerkz.bottlerocket.clusters.MongoCluster;
import com.antwerkz.bottlerocket.clusters.ReplicaSet;
import com.antwerkz.bottlerocket.clusters.SingleNode;
import com.antwerkz.bottlerocket.configuration.types.Verbosity;
import com.github.zafarkhaja.semver.Version;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoClientSettings.Builder;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.lang.NonNull;
import dev.morphia.Datastore;
import dev.morphia.Morphia;
import dev.morphia.mapping.Mapper;
import dev.morphia.mapping.MapperOptions;
import dev.morphia.mapping.codec.reader.DocumentReader;
import dev.morphia.mapping.codec.writer.DocumentWriter;
import dev.morphia.query.DefaultQueryFactory;
import dev.morphia.query.LegacyQueryFactory;
import org.apache.commons.io.FileUtils;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.function.Supplier;

import static dev.morphia.internal.MorphiaInternals.proxyClassesPresent;
import static java.lang.String.format;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public abstract class TestBase {
    protected static final String TEST_DB_NAME = "morphia_test";
    private static final Logger LOG = LoggerFactory.getLogger(TestBase.class);
    private static MongoClient mongoClient;

    private MapperOptions mapperOptions;
    private MongoDatabase database;
    private Datastore datastore;

    public TestBase() {
        mapperOptions = MapperOptions.DEFAULT;
    }

    public TestBase(MapperOptions mapperOptions) {
        this.mapperOptions = mapperOptions;
    }

    public void assertTrueLazy(boolean condition, Supplier<String> messageSupplier) {
        if (!condition) {
            fail(messageSupplier.get());
        }
    }

    @BeforeMethod
    public void beforeEach() {
        cleanup();
    }

    public MongoDatabase getDatabase() {
        if (database == null) {
            database = getDs().getDatabase();
        }
        return database;
    }

    public Datastore getDs() {
        if (datastore == null) {
            datastore = Morphia.createDatastore(getMongoClient(), TEST_DB_NAME, mapperOptions);
        }
        return datastore;
    }

    public Mapper getMapper() {
        return getDs().getMapper();
    }

    public void installData() {
        File file = new File("zips.json");
        try {
            if (!file.exists()) {
                file = new File("target/zips.json");
                if (!file.exists()) {
                    download(new URL("https://media.mongodb.org/zips.json"), file);
                }
            }
            MongoCollection<Document> zips = getDatabase().getCollection("zipcodes");
            if (zips.countDocuments() == 0) {
                LOG.info("Count is 0.  (Re)installing sample data");
                MongoCollection<Document> zipcodes = getDatabase().getCollection("zipcodes");
                zipcodes.drop();
                Files.lines(file.toPath())
                     .forEach(l -> zipcodes.insertOne(Document.parse(l)));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        assumeTrue(file.exists(), "Failed to process media files");
    }

    public boolean isReplicaSet() {
        return runIsMaster().get("setName") != null;
    }

    public void lazyAssert(Supplier<String> messageSupplier, Runnable assertion) {
        try {
            assertion.run();
        } catch (AssertionError error) {
            fail(messageSupplier.get(), error);
        }
    }

    @DataProvider(name = "mapperOptions")
    public Object[][] mapperOptions() {
        return new Object[][]{
            new Object[]{MapperOptions.DEFAULT},
            new Object[]{MapperOptions.legacy().build()}
        };
    }

    @DataProvider(name = "queryFactories")
    public Object[][] queryFactories() {
        return new Object[][]{
            new Object[]{new DefaultQueryFactory()},
            new Object[]{new LegacyQueryFactory()}
        };
    }

    protected void assertCapped(Class<?> type, Integer max, Integer size) {
        Document result = getOptions(type);
        Assert.assertTrue(result.getBoolean("capped"));
        assertEquals(result.get("max"), max);
        assertEquals(result.get("size"), size);
    }

    protected void assertDocumentEquals(Object actual, Object expected) {
        assertDocumentEquals("", actual, expected);
    }

    protected void assertListEquals(Collection<?> actual, Collection<?> expected) {
        assertEquals(actual.size(), expected.size());
        expected.forEach(
            d -> assertTrueLazy(actual.contains(d), () -> format("Should have found <<%s>> in the actual list:%n%s", d, actual)));
    }

    protected void assumeTrue(boolean condition, String message) {
        if (!condition) {
            throw new SkipException(message);
        }
    }

    protected void checkForProxyTypes() {
        assumeTrue(proxyClassesPresent(), "Proxy classes are needed for this test");
    }

    protected void checkMinServerVersion(double version) {
        checkMinServerVersion(Version.valueOf(version + ".0"));
    }

    protected void checkMinServerVersion(Version version) {
        assumeTrue(serverIsAtLeastVersion(version),
            String.format("Server should be at least %s but found %s", version, getServerVersion()));
    }

    protected int count(MongoCursor<?> cursor) {
        int count = 0;
        while (cursor.hasNext()) {
            cursor.next();
            count++;
        }
        return count;
    }

    protected int count(Iterator<?> iterator) {
        int count = 0;
        while (iterator.hasNext()) {
            count++;
            iterator.next();
        }
        return count;
    }

    protected void download(URL url, File file) throws IOException {
        LOG.info("Downloading zip data set to " + file);
        try (InputStream inputStream = url.openStream(); FileOutputStream outputStream = new FileOutputStream(file)) {
            byte[] read = new byte[49152];
            int count;
            while ((count = inputStream.read(read)) != -1) {
                outputStream.write(read, 0, count);
            }
        }
    }

    protected <T> T fromDocument(Class<T> type, Document document) {
        Class<T> aClass = type;
        Mapper mapper = getMapper();
        if (document.containsKey(mapper.getOptions().getDiscriminatorKey())) {
            aClass = mapper.getClass(document);
        }

        DocumentReader reader = new DocumentReader(document);

        return getDs().getCodecRegistry()
                      .get(aClass)
                      .decode(reader, DecoderContext.builder().build());
    }

    protected MongoCollection<Document> getDocumentCollection(Class<?> type) {
        return getDatabase().getCollection(getMapper().getEntityModel(type).getCollectionName());
    }

    protected List<Document> getIndexInfo(Class<?> clazz) {
        return getDs().getCollection(clazz).listIndexes().into(new ArrayList<>());
    }

    protected MongoClient getMongoClient() {
        if (mongoClient == null) {
            startMongo();
        }
        return mongoClient;
    }

    @NonNull
    protected Document getOptions(Class<?> type) {
        String collection = getMapper().getEntityModel(type).getCollectionName();
        Document result = getDatabase().runCommand(new Document("listCollections", 1.0)
            .append("filter",
                new Document("name", collection)));

        Document cursor = (Document) result.get("cursor");
        return (Document) cursor.getList("firstBatch", Document.class)
                                .get(0)
                                .get("options");
    }

    protected Version getServerVersion() {
        String version = (String) getMongoClient()
                                      .getDatabase("admin")
                                      .runCommand(new Document("serverStatus", 1))
                                      .get("version");
        return Version.valueOf(version);
    }

    protected void insert(String collectionName, List<Document> list) {
        MongoCollection<Document> collection = getDatabase().getCollection(collectionName);
        collection.deleteMany(new Document());
        collection.insertMany(list);
    }

    /**
     * @param version the minimum version allowed
     * @return true if server is at least specified version
     */
    protected boolean serverIsAtLeastVersion(Version version) {
        return getServerVersion().greaterThanOrEqualTo(version);
    }

    protected Document toDocument(Object entity) {
        final Class<?> type = getMapper().getEntityModel(entity.getClass()).getType();

        DocumentWriter writer = new DocumentWriter(getMapper());
        ((Codec) getDs().getCodecRegistry().get(type)).encode(writer, entity, EncoderContext.builder().build());

        return writer.getDocument();
    }

    protected String toString(Document document) {
        return document.toJson(getDs().getCodecRegistry().get(Document.class));
    }

    protected void withOptions(MapperOptions options, Runnable block) {
        MapperOptions previousOptions = mapperOptions;
        try {
            mapperOptions = options;
            database = null;
            datastore = null;

            block.run();
        } finally {
            mapperOptions = previousOptions;
            database = null;
            datastore = null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertDocumentEquals(String path, Object actual, Object expected) {
        assertSameNullity(path, expected, actual);
        if (expected == null) {
            return;
        }
        assertSameType(path, actual, expected);

        if (expected instanceof Document) {
            for (Entry<String, Object> entry : ((Document) expected).entrySet()) {
                final String key = entry.getKey();
                Object expectedValue = entry.getValue();
                Object actualValue = ((Document) actual).get(key);
                assertDocumentEquals(path + "." + key, actualValue, expectedValue);
            }
        } else if (expected instanceof List) {
            List list = (List) expected;
            List copy = new ArrayList<>((List) actual);

            Object o;
            for (int i = 0; i < list.size(); i++) {
                o = list.get(i);
                boolean found = false;
                final Iterator other = copy.iterator();
                while (!found && other.hasNext()) {
                    try {
                        String newPath = format("%s[%d]", path, i);
                        assertDocumentEquals(newPath, other.next(), o);
                        other.remove();
                        found = true;
                    } catch (AssertionError ignore) {
                    }
                }
                if (!found) {
                    fail(format("mismatch found at %s", path));
                }
            }
        } else {
            assertEquals(actual, expected, format("mismatch found at %s:%n%s vs %s", path, expected, actual));
        }
    }

    private void assertSameNullity(String path, Object expected, Object actual) {
        if (expected == null && actual != null
            || actual == null && expected != null) {
            assertEquals(actual, expected, format("mismatch found at %s:%n%s vs %s", path, expected, actual));
        }
    }

    private void assertSameType(String path, Object actual, Object expected) {
        if (expected instanceof List && actual instanceof List) {
            return;
        }
        if (!expected.getClass().equals(actual.getClass())) {
            assertEquals(actual, expected, format("mismatch found at %s:%n%s vs %s", path, expected, actual));
        }
    }

    protected void cleanup() {
        database = null;
        datastore = null;
        MongoDatabase db = getDatabase();
        db.runCommand(new Document("profile", 0).append("slowms", 0));
        db.listCollectionNames().forEach(s -> {
            if (!s.equals("zipcodes") && !s.startsWith("system")) {
                db.getCollection(s).drop();
            }
        });
    }

    private Document runIsMaster() {
        return mongoClient.getDatabase("admin")
                          .runCommand(new Document("ismaster", 1));
    }

    private void startMongo() {
        String mongodb = System.getenv("MONGODB");
        Builder builder = MongoClientSettings.builder();

        try {
            builder.uuidRepresentation(mapperOptions.getUuidRepresentation());
        } catch (Exception ignored) {
            // not a 4.0 driver
        }

        if (mongodb != null) {
            File mongodbRoot = new File("target/mongo");
            try {
                FileUtils.deleteDirectory(mongodbRoot);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
            Version version = Version.valueOf(mongodb);
            final MongoCluster cluster = version.lessThan(Version.valueOf("4.0.0"))
                                         ? new SingleNode(mongodbRoot, "morphia_test", version)
                                         : new ReplicaSet(mongodbRoot, "morphia_test", version);

            cluster.configure(c -> {
                c.systemLog(s -> {
                    s.setTraceAllExceptions(true);
                    s.setVerbosity(Verbosity.FIVE);
                    return null;
                });
                return null;
            });
            cluster.clean();
            cluster.start();
            mongoClient = cluster.getClient(builder);
        } else {
            mongoClient = MongoClients.create(builder.build());
        }
    }
}
