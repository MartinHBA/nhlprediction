package sk.nhl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.*;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.*;
import org.apache.parquet.io.api.*;
import org.apache.parquet.example.data.*;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.schema.*;

/** Bounded download and projected, row-group-at-a-time reading of the public mirror. */
final class ParquetData {
    static final int MAX_BYTES=96*1024*1024;
    static void read(MirrorSources.Asset asset,Set<String> columns,Consumer<Map<String,String>> consumer){
        Path file=null;
        try{
            file=Files.createTempFile("nhl-mirror-",".parquet");
            URI uri=URI.create(asset.url());
            for(int redirects=0;;redirects++){
                allowed(uri);
                var response=Schedule.HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("User-Agent","NHL-Observatory/2.2").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                try(var in=response.body()){
                    if(Set.of(301,302,303,307,308).contains(response.statusCode())){
                        if(redirects>=3)throw new IOException("Too many release redirects");
                        uri=uri.resolve(response.headers().firstValue("Location").orElseThrow());continue;
                    }
                    if(response.statusCode()!=200)throw new IOException("Release download HTTP "+response.statusCode());
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");int total=0;
                    ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();var stop=timer.schedule(()->{try{in.close();}catch(IOException ignored){}},90,TimeUnit.SECONDS);
                    try(var out=Files.newOutputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){total+=n;if(total>MAX_BYTES)throw new IOException("Parquet size limit exceeded");digest.update(buffer,0,n);out.write(buffer,0,n);}}
                    finally{stop.cancel(false);timer.shutdownNow();}
                    String actual="sha256:"+HexFormat.of().formatHex(digest.digest());
                    if(!asset.digest().isBlank()&&!actual.equals(asset.digest()))throw new IOException("GitHub asset digest mismatch");
                    break;
                }
            }
            readFile(file,columns,consumer);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Mirror download interrupted",e);}
        catch(Exception e){throw new IllegalStateException("Parquet mirror: "+e.getMessage(),e);}
        finally{if(file!=null)try{Files.deleteIfExists(file);}catch(IOException ignored){}}
    }
    static void allowed(URI uri){
        if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getPort()!=-1||!Set.of("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com").contains(uri.getHost()))throw new IllegalArgumentException("Unsupported release redirect");
        if(uri.getHost().equals("github.com")&&!uri.getPath().startsWith(MirrorSources.RELEASE_PATH))throw new IllegalArgumentException("Unsupported release path");
    }
    static void readFile(Path path,Set<String> columns,Consumer<Map<String,String>> consumer)throws IOException{
        try(var reader=ParquetFileReader.open(new LocalInputFile(path),ParquetReadOptions.builder(new PlainParquetConfiguration()).build())){
            MessageType schema=reader.getFooter().getFileMetaData().getSchema();List<Type> fields=new ArrayList<>();
            for(Type field:schema.getFields())if(columns.isEmpty()||columns.contains(field.getName().toLowerCase(Locale.ROOT)))fields.add(field);
            if(fields.isEmpty())throw new IOException("Required columns absent");
            MessageType projection=new MessageType(schema.getName(),fields);reader.setRequestedSchema(projection);
            var columnIO=new ColumnIOFactory().getColumnIO(projection);long count=0;
            org.apache.parquet.column.page.PageReadStore pages;
            while((pages=reader.readNextRowGroup())!=null){
                if((count+=pages.getRowCount())>2_000_000)throw new IOException("Parquet row limit exceeded");
                var records=columnIO.getRecordReader(pages,new GroupRecordConverter(projection));
                for(long i=0;i<pages.getRowCount();i++){
                    Group group=records.read();Map<String,String> row=new HashMap<>();
                    for(int c=0;c<fields.size();c++){
                        if(!fields.get(c).isPrimitive()||group.getFieldRepetitionCount(c)>1)throw new IOException("Expected scalar Parquet columns");
                        if(group.getFieldRepetitionCount(c)==0)continue;
                        var primitive=fields.get(c).asPrimitiveType().getPrimitiveTypeName();
                        String value=switch(primitive){
                            case BINARY,FIXED_LEN_BYTE_ARRAY -> new String(group.getBinary(c,0).getBytes(),StandardCharsets.UTF_8);
                            case INT32 -> Integer.toString(group.getInteger(c,0));
                            case INT64 -> Long.toString(group.getLong(c,0));
                            case DOUBLE -> Double.toString(group.getDouble(c,0));
                            case FLOAT -> Float.toString(group.getFloat(c,0));
                            case BOOLEAN -> Boolean.toString(group.getBoolean(c,0));
                            default -> throw new IOException("Unsupported Parquet primitive "+primitive);
                        };
                        row.put(fields.get(c).getName().toLowerCase(Locale.ROOT),value);
                    }
                    consumer.accept(row);
                }
            }
        }
    }
}
