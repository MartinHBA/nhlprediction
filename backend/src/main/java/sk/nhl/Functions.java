package sk.nhl;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.azure.storage.queue.*;
import com.azure.storage.queue.models.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static sk.nhl.Json.*;

public final class Functions {
    static final class Services {
        static final Store STORE=Store.cloud();
        static final QueueClient QUEUE=new QueueClientBuilder().connectionString(System.getenv("AzureWebJobsStorage")).queueName("predictions").messageEncoding(QueueMessageEncoding.BASE64).buildClient();
        static final Workflow WORKFLOW=new Workflow(STORE,Schedule::fetch,j->QUEUE.sendMessage(j.toString()),new DataCollector(STORE),new GameScores());
        static final OAuth AUTH=new OAuth(STORE);
        static final Mcp MCP=new Mcp(WORKFLOW);
    }
    @FunctionName("http")
    public HttpResponseMessage http(@HttpTrigger(name="request",methods={HttpMethod.GET,HttpMethod.POST,HttpMethod.DELETE,HttpMethod.OPTIONS},authLevel=AuthorizationLevel.ANONYMOUS,route="{*path}")HttpRequestMessage<Optional<String>> request,ExecutionContext context){
        String path=request.getUri().getPath(),method=request.getHttpMethod().name();
        try{
            if(request.getBody().orElse("").length()>2_000_000)return response(request,413,obj("error","Request too large"));
            if(method.equals("GET")&&path.equals("/health"))return response(request,200,obj("status","ok","version",Mcp.VERSION,"ai_api_calls",false,"prediction_model",false));
            if(method.equals("OPTIONS"))return response(request,204,null);
            if(method.equals("GET")&&(path.equals("/.well-known/oauth-protected-resource")||path.equals("/.well-known/oauth-protected-resource/mcp")))return response(request,200,Services.AUTH.resourceMetadata());
            if(method.equals("GET")&&(path.equals("/.well-known/oauth-authorization-server")||path.equals("/.well-known/openid-configuration")))return response(request,200,Services.AUTH.metadata());
            if(path.startsWith("/oauth/")){
                OAuth.Reply reply=switch(path){
                    case "/oauth/register" -> {requireMethod(method,"POST");yield Services.AUTH.register(parse(request.getBody().orElse("{}")));}
                    case "/oauth/authorize" -> {requireMethod(method,"GET");yield Services.AUTH.authorize(OAuth.form(request.getUri().getRawQuery()));}
                    case "/oauth/microsoft/callback" -> {requireMethod(method,"GET");yield Services.AUTH.callback(OAuth.form(request.getUri().getRawQuery()));}
                    case "/oauth/token" -> {requireMethod(method,"POST");yield Services.AUTH.token(OAuth.form(request.getBody().orElse("")));}
                    default -> new OAuth.Reply(404,obj("error","not_found"),null);
                };
                if(reply.location()!=null)return request.createResponseBuilder(HttpStatus.FOUND).header("Location",reply.location()).header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").build();
                return response(request,reply.status(),reply.body());
            }
            if(path.equals("/mcp")){
                String origin=header(request,"origin");if(origin!=null&&!origin.equals("https://chatgpt.com")&&!origin.equals(System.getenv("WEB_URL")))return response(request,403,obj("error","Origin not allowed"));
                if(!Services.AUTH.authenticated(header(request,"authorization")))return request.createResponseBuilder(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate","Bearer resource_metadata=\""+System.getenv("PUBLIC_BASE_URL")+"/.well-known/oauth-protected-resource\"").header("Cache-Control","no-store").body(obj("error","authentication_required").toString()).build();
                if(!method.equals("POST"))return response(request,405,obj("error","Use POST; this MCP transport is stateless"));
                var input=parse(request.getBody().orElse("{}"));if(!input.isObject())return response(request,400,Mcp.error(com.fasterxml.jackson.databind.node.NullNode.instance,-32600,"JSON-RPC object required"));
                ObjectNode result=Services.MCP.handle(input);return response(request,result==null?202:200,result);
            }
            if(method.equals("GET")&&path.equals("/api/days"))return response(request,200,obj("days",Services.WORKFLOW.archive()));
            if(method.equals("GET")&&path.matches("/api/days/\\d{4}-\\d{2}-\\d{2}/cards")){
                String day=path.substring(10,20);ObjectNode d=Services.STORE.get("days/"+day+".json");if(d==null)return response(request,404,obj("error","Day has not been collected yet"));
                return response(request,200,Services.WORKFLOW.readCards(d));
            }
            if(method.equals("GET")&&path.matches("/api/days/\\d{4}-\\d{2}-\\d{2}/live")){
                String day=path.substring(10,20);if(Services.STORE.get("days/"+day+".json")==null)return response(request,404,obj("error","Day has not been collected yet"));
                return response(request,200,Services.WORKFLOW.readLive(day));
            }
            if(method.equals("GET")&&path.matches("/api/days/\\d{4}-\\d{2}-\\d{2}")){
                String day=path.substring(10);ObjectNode d=Services.STORE.get("days/"+day+".json");if(d==null)return response(request,404,obj("error","Day has not been collected yet","day",day));
                return response(request,200,Services.WORKFLOW.read(d));
            }
            if(method.equals("GET")&&path.equals("/api/schema"))return response(request,200,resource("schema.json"));
            return response(request,404,obj("error","Not found"));
        }catch(BusyException e){return response(request,409,obj("error",e.getMessage()));}catch(IllegalArgumentException e){return response(request,400,obj("error",e.getMessage()));}catch(Exception e){context.getLogger().severe("Request failed: "+e.getClass().getSimpleName());return response(request,503,obj("error","Service temporarily unavailable. Retry shortly."));}
    }
    static String header(HttpRequestMessage<?>request,String key){return request.getHeaders().entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(key)).map(Map.Entry::getValue).findFirst().orElse(null);}
    static void requireMethod(String actual,String expected){if(!actual.equals(expected))throw new IllegalArgumentException("Expected "+expected);}
    static HttpResponseMessage response(HttpRequestMessage<?>r,int status,com.fasterxml.jackson.databind.JsonNode body){return r.createResponseBuilder(HttpStatus.valueOf(status)).header("Content-Type","application/json; charset=utf-8").header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer").body(body==null?null:body.toString()).build();}
    @FunctionName("importData")
    public void importData(@QueueTrigger(name="job",queueName="predictions",connection="AzureWebJobsStorage")String job,ExecutionContext context){Services.WORKFLOW.processImport(parse(job));}
    @FunctionName("oddsWatch")
    public void oddsWatch(@TimerTrigger(name="timer",schedule="0 0,30 * * * *")String timer,ExecutionContext context){
        var now=java.time.Instant.now();if(NhlOdds.pollingWindow(now))Services.WORKFLOW.eveningTick(now);else if(Schedule.programWindow(now))Services.WORKFLOW.programTick(now);
    }
    @FunctionName("oddizeSnapshot")
    public void oddizeSnapshot(@TimerTrigger(name="timer",schedule="0 45 * * * *")String timer,ExecutionContext context){
        var now=java.time.Instant.now();if(OddizeOdds.snapshotWindow(now))Services.WORKFLOW.oddsSnapshotTick(now);
    }
    @FunctionName("maintenance")
    public void maintenance(@TimerTrigger(name="timer",schedule="0 */15 * * * *")String timer,ExecutionContext context){
        Services.AUTH.cleanup();
        for(String key:Services.STORE.keys("days/")){
            ObjectNode d=Services.STORE.get(key);if(d==null)continue;
            if(List.of("PREFILLING").contains(text(d,"state"))&&Checks.time(text(d,"updated_at")).isBefore(java.time.Instant.now().minusSeconds(7200)))Services.STORE.locked("day/"+text(d,"day"),()->{ObjectNode latest=Services.STORE.get(key);if(latest!=null&&List.of("PREFILLING").contains(text(latest,"state"))&&Checks.time(text(latest,"updated_at")).isBefore(java.time.Instant.now().minusSeconds(7200))){latest.put("state","ERROR");latest.put("error","Source import did not finish. Resume the ChatGPT task.");Services.STORE.put(key,latest);}return null;});
        }
    }
}
