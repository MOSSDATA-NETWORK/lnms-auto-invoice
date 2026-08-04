package com.autoinvoice.worker.storage;

public interface ObjectStorage {
    StoredObject put(String objectKey, byte[] bytes, String contentType) throws Exception;

    byte[] get(String bucket, String objectKey) throws Exception;

    record StoredObject(String provider, String bucket, String objectKey) {
    }
}
