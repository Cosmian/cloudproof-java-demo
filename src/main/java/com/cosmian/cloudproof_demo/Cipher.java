package com.cosmian.cloudproof_demo;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.cosmian.CosmianException;
import com.cosmian.cloudproof_demo.fs.InputPath;
import com.cosmian.cloudproof_demo.fs.LocalFileSystem;
import com.cosmian.cloudproof_demo.fs.OutputDirectory;
import com.cosmian.cloudproof_demo.sse.Sse;
import com.cosmian.cloudproof_demo.sse.Sse.DbUid;
import com.cosmian.cloudproof_demo.sse.Sse.Key;
import com.cosmian.cloudproof_demo.sse.Sse.Word;
import com.cosmian.jna.Ffi;
import com.cosmian.jna.FfiException;
import com.cosmian.jna.abe.EncryptedHeader;
import com.cosmian.rest.abe.acccess_policy.Attr;
import com.cosmian.rest.kmip.objects.PublicKey;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JsonProvider;

public class Cipher implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(Cipher.class.getName());

    final Path abePublicKeyFile;

    final List<String> inputs;

    final OutputDirectory output;

    final Key k;

    final Key kStar;

    final DseDB sseDb;

    public Cipher(Path abePublicKeyFile, List<String> inputs, String outputDirectory, DseDB.Configuration dseConf)
            throws AppException {
        this.abePublicKeyFile = abePublicKeyFile;
        this.inputs = inputs;
        this.output = OutputDirectory.parse(outputDirectory);
        LocalFileSystem fs = new LocalFileSystem();
        Path keysDirectory = abePublicKeyFile.getParent();
        File kFile = keysDirectory.resolve(KeyGenerator.SSE_K_KEY_FILENAME).toFile();
        this.k = new Key(fs.readFile(kFile));
        File kStarFile = keysDirectory.resolve(KeyGenerator.SSE_K_STAR_KEY_FILENAME).toFile();
        this.kStar = new Key(fs.readFile(kStarFile));
        try {
            this.sseDb = new DseDB(dseConf);
            this.sseDb.truncateEntryTable();
            this.sseDb.truncateChainTable();
        } catch (CosmianException e) {
            throw new AppException("Failed initializing the SSE DB: " + e.getMessage(), e);
        }
    }

    public void run() throws AppException {

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("sha-256");
        } catch (NoSuchAlgorithmException e1) {
            throw new AppException("Sha 256 is not supported on this machine; cannot continue");
        }

        // Create a cache of the Public Key and Policy
        int cacheHandle;
        try {
            String publicKeyJson = LocalResource.load_file_string(this.abePublicKeyFile.toFile());
            PublicKey publicKey = PublicKey.fromJson(publicKeyJson);
            cacheHandle = Ffi.createEncryptionCache(publicKey);
        } catch (CosmianException e) {
            throw new AppException("Failed processing the public key file:" + e.getMessage(), e);
        } catch (IOException e) {
            throw new AppException("Failed loading the public key file:" + e.getMessage(), e);
        } catch (FfiException e) {
            throw new AppException("Failed creating the cache:" + e.getMessage(), e);
        }

        try {
            // Provider that reads Json Paths
            JsonProvider jsonProvider = Configuration.defaultConfiguration().jsonProvider();

            for (String inputPathString : this.inputs) {
                InputPath inputPath = InputPath.parse(inputPathString);
                Iterator<String> it = inputPath.listFiles();
                while (it.hasNext()) {
                    String inputFile = it.next();
                    try {
                        final long then = System.nanoTime();
                        long[] results = processResource(jsonProvider, md, cacheHandle,
                                inputPath.getFs().getInputStream(inputFile));
                        final long totalTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - then);
                        long totalEncryptionTime = results[0];
                        long totalLines = results[1];
                        long totalSseTime = results[2];
                        logger.info("Processed " + totalLines + " lines from " + inputFile + " in " + totalTime
                                + " ms (indexing time: " + (totalSseTime * 100 / totalTime) + "%, abe encryption time: "
                                + (totalEncryptionTime * 100 / totalTime)
                                + "%). Average ABE encryption time per line: " + (totalEncryptionTime / totalLines)
                                + " ms.");
                    } catch (AppException e) {
                        logger.severe("Aborting processing of the file: " + inputFile + ": " + e.getMessage());
                    }
                }
            }
        } finally {
            // The cache should be destroyed to reclaim memory
            try {
                Ffi.destroyEncryptionCache(cacheHandle);
            } catch (FfiException | CosmianException e) {
                logger.warning("Failed destroying the encryption cache and reclaiming memory: " + e.getMessage());
            }
        }
    }

    /**
     * Process the resource, returning the average encryption time in millis
     */
    long[] processResource(JsonProvider jsonProvider, MessageDigest sha256, int cache, InputStream is)
            throws AppException {
        // SSE Indexes-we want to accumulate some o that the servers does not learn
        // anything by running statistical analysis on what is inserted
        // So say we want to run insert batches of
        final int SSE_BATCH = 100;

        HashMap<DbUid, Set<Word>> dbUidToWords = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            long numLines = 0;
            long totalTime = 0;
            long sseTime = 0;
            while ((line = br.readLine()) != null) {
                if (line.trim().length() == 0) {
                    continue;
                }
                byte[] lineBytes = line.getBytes(StandardCharsets.UTF_8);
                byte[] hash = sha256.digest(lineBytes);
                String encryptedFileName = Base58.encode(hash);

                Object document = jsonProvider.parse(lineBytes);
                // Determine indexes
                final String direction = ((String) JsonPath.read(document,
                        "$.payload.businessPaymentInformation.pmtBizCntxt.drctn.ITRId"))
                        .toUpperCase();
                String IBAN;
                if (direction.equals("IN")) {
                    IBAN = JsonPath.read(document, "$.payload.businessPaymentInformation.cdtr.cdtrAcct");
                } else if (direction.equals("OUT")) {
                    IBAN = JsonPath.read(document, "$.payload.businessPaymentInformation.dbtr.dbtrAcct");
                } else {
                    logger.severe("Unknown Direction: " + direction.toUpperCase() + "! Skipping record");
                    continue;
                }
                final String country = IBAN.substring(0, 2).toUpperCase();
                logger.fine(() -> "Indexing " + encryptedFileName + " [" + direction + ", " + country + "]");
                HashSet<Word> set = new HashSet<>(2);
                set.add(new Word(direction.getBytes(StandardCharsets.UTF_8)));
                set.add(new Word(country.getBytes(StandardCharsets.UTF_8)));
                dbUidToWords.put(new DbUid(hash), set);
                if (dbUidToWords.size() >= SSE_BATCH) {
                    try {
                        final long sseThen = System.nanoTime();
                        Sse.bulkUpsert(this.k, this.kStar, dbUidToWords, this.sseDb);
                        sseTime += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sseThen);
                    } catch (CosmianException e) {
                        throw new AppException("Failed upserting the indexes: " + e.getMessage(), e);
                    }
                    dbUidToWords.clear();
                }

                // Map process ID to attributes
                String processId = JsonPath.read(document, "$.header.functional.currentEventProducer.processId");
                Attr[] attributes;
                try {
                    attributes = getAttributes(processId);
                } catch (CosmianException e) {
                    throw new AppException("Failed Creating the PolicyAttributes: " + e.getMessage());
                }

                // Measure Encryption time (quick and dirt - need a micro benchmark tool to do
                // this properly)
                final long then = System.nanoTime();
                EncryptedHeader encryptedHeader;
                try {
                    encryptedHeader = Ffi.encryptHeaderUsingCache(cache, attributes);
                } catch (FfiException | CosmianException e) {
                    throw new AppException("Failed to encrypt the header: " + e.getMessage(), e);
                }
                byte[] encryptedBlock;
                try {
                    encryptedBlock = Ffi.encryptBlock(encryptedHeader.getSymmetricKey(), hash, 0, lineBytes);
                } catch (FfiException e) {
                    throw new AppException("Failed to encrypt the content: " + e.getMessage(), e);
                }
                totalTime += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - then);

                // The size of the header as an int in BE bytes
                ByteBuffer headerSize = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                        .putInt(encryptedHeader.getEncryptedHeaderBytes().length);

                // Write the result
                ByteArrayOutputStream bao = new ByteArrayOutputStream();
                bao.write(headerSize.array());
                bao.write(encryptedHeader.getEncryptedHeaderBytes());
                bao.write(encryptedBlock);
                bao.flush();

                this.output.getFs().writeFile(this.output.getDirectory().resolve(encryptedFileName).toString(),
                        bao.toByteArray());

                numLines += 1;
            }
            // flush the remaining SSE indexes
            if (dbUidToWords.size() >= 0) {
                try {
                    final long sseThen = System.nanoTime();
                    Sse.bulkUpsert(this.k, this.kStar, dbUidToWords, this.sseDb);
                    sseTime += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sseThen);
                } catch (CosmianException e) {
                    throw new AppException("Failed upserting the indexes: " + e.getMessage(), e);
                }
            }
            return new long[] { totalTime, numLines, sseTime };
        } catch (IOException e) {
            throw new AppException("an /IO Error occurred:" + e.getMessage(), e);
        }
    }

    Attr[] getAttributes(String processId) throws CosmianException {

        if (processId.startsWith("EUDBD101")) {
            return new Attr[] { new Attr("Entity", "BNPPF"), new Attr("Country", "France") };
        }

        if (processId.startsWith("EUDBD501")) {
            return new Attr[] { new Attr("Entity", "BNPPF"), new Attr("Country", "Italy") };
        }

        if (processId.startsWith("EUDBD601")) {
            return new Attr[] { new Attr("Entity", "BCEF"), new Attr("Country", "France") };
        }

        if (processId.startsWith("EUDBD701")) {
            return new Attr[] { new Attr("Entity", "CIB"), new Attr("Country", "Belgium") };
        }

        return new Attr[] { new Attr("Entity", "CashMgt"), new Attr("Country", "France") };

    }

    @Override
    public void close() {
        this.sseDb.close();
    }

}