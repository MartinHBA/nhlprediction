package sk.nhl;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.*;
import com.azure.storage.blob.models.*;
import com.azure.storage.blob.options.*;
import com.azure.storage.blob.specialized.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import static sk.nhl.Json.*;

interface Store {
    ObjectNode get(String key);
    void put(String key,ObjectNode value);
    void create(String key,ObjectNode value);
    ObjectNode take(String key);
    List<String> keys(String prefix);
    <T>T locked(String key,Supplier<T> operation);
    static Store cloud(){return new AzureStore();}
}
final class AzureStore implements Store {
    final BlobContainerClient container;
    AzureStore(){container=new BlobServiceClientBuilder().connectionString(System.getenv("AzureWebJobsStorage")).buildClient().getBlobContainerClient("nhl-private");}
    public ObjectNode get(String key){try{return (ObjectNode)parse(container.getBlobClient(key).downloadContent().toString());}catch(BlobStorageException e){if(e.getStatusCode()==404)return null;throw e;}}
    public void put(String key,ObjectNode value){container.getBlobClient(key).upload(BinaryData.fromString(value.toString()),true);}
    public void create(String key,ObjectNode value){container.getBlobClient(key).upload(BinaryData.fromString(value.toString()),false);}
    public List<String> keys(String prefix){List<String>k=new ArrayList<>();container.listBlobs(new ListBlobsOptions().setPrefix(prefix),Duration.ofSeconds(15)).forEach(b->k.add(b.getName()));return k;}
    public ObjectNode take(String key){return locked("consume/"+key,()->{ObjectNode v=get(key);if(v!=null)container.getBlobClient(key).delete();return v;});}
    public <T>T locked(String key,Supplier<T> operation){
        BlobClient blob=container.getBlobClient("locks/"+key);
        try{blob.upload(BinaryData.fromString("lock"),false);}catch(BlobStorageException e){if(e.getStatusCode()!=409)throw e;}
        BlobLeaseClient lease=new BlobLeaseClientBuilder().blobClient(blob).buildClient();
        try{lease.acquireLease(60);}catch(BlobStorageException e){if(e.getStatusCode()==409)throw new BusyException();throw e;}
        try{return operation.get();}finally{lease.releaseLease();}
    }
}
final class BusyException extends RuntimeException {BusyException(){super("Another update is running. Retry shortly.");}}
