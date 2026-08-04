package com.autoinvoice.api.storage;

import com.autoinvoice.platform.storage.BoundedObjectReader;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

@Component
public class ApiObjectStorage {
    private final MinioClient client;
    private final String bucket;

    public ApiObjectStorage(@Value("${auto-invoice.storage.endpoint:http://localhost:9000}") String endpoint,
                            @Value("${auto-invoice.storage.access-key:minioadmin}") String accessKey,
                            @Value("${auto-invoice.storage.secret-key:minioadmin}") String secretKey,
                            @Value("${auto-invoice.storage.bucket:auto-invoice}") String bucket) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    public StoredObject put(String objectKey, byte[] bytes, String contentType) throws Exception {
        client.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey).contentType(contentType)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
        return new StoredObject("MINIO", bucket, objectKey);
    }

    public byte[] get(String sourceBucket, String objectKey) throws Exception {
        try (var stream = client.getObject(GetObjectArgs.builder().bucket(sourceBucket).object(objectKey).build())) {
            return BoundedObjectReader.read(stream);
        }
    }

    public record StoredObject(String provider, String bucket, String objectKey) {
    }
}
