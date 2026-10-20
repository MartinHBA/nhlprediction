package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import static sk.nhl.Json.*;

/** Small single-owner OAuth authorization server. Microsoft handles interactive login;
 * downstream MCP credentials are opaque, audience-bound, short-lived and never sent to the browser URL. */
final class OAuth {
    record Reply(int status,ObjectNode body,String location) {}
    final Store store;final String base,tenant,client,secret,owner;
    static final SecureRandom RANDOM=new SecureRandom();
    static final String SCOPE="nhl.manage";
    private static volatile JWKSet microsoftKeys;
    private static volatile Instant keysExpire=Instant.EPOCH;
    OAuth(Store store){this(store,System.getenv("PUBLIC_BASE_URL"),System.getenv("AUTH_TENANT_ID"),System.getenv("AUTH_CLIENT_ID"),System.getenv("AUTH_CLIENT_SECRET"),System.getenv("AUTH_OWNER_ID"));}
    OAuth(Store s,String base,String tenant,String client,String secret,String owner){this.store=s;this.base=base;this.tenant=tenant;this.client=client;this.secret=secret;this.owner=owner;}
    static String random(){byte[]b=new byte[32];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    static String hash(String s){try{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    static Map<String,String> form(String s){Map<String,String>m=new HashMap<>();if(s==null||s.isBlank())return m;for(String pair:s.split("&")){String[]p=pair.split("=",2);String key=URLDecoder.decode(p[0],StandardCharsets.UTF_8);if(m.put(key,p.length==2?URLDecoder.decode(p[1],StandardCharsets.UTF_8):"")!=null)throw new IllegalArgumentException("Duplicate OAuth parameter");}return m;}
    static boolean allowedRedirect(String s){return s!=null&&(s.equals("https://chatgpt.com/connector_platform_oauth_redirect")||s.matches("https://chatgpt\\.com/connector/oauth/[A-Za-z0-9_-]{1,200}"));}
    ObjectNode resourceMetadata(){return obj("resource",base+"/mcp","authorization_servers",List.of(base),"scopes_supported",List.of(SCOPE),"bearer_methods_supported",List.of("header"));}
    ObjectNode metadata(){return obj("issuer",base,"authorization_endpoint",base+"/oauth/authorize","token_endpoint",base+"/oauth/token","registration_endpoint",base+"/oauth/register","response_types_supported",List.of("code"),"grant_types_supported",List.of("authorization_code","refresh_token"),"token_endpoint_auth_methods_supported",List.of("none"),"code_challenge_methods_supported",List.of("S256"),"scopes_supported",List.of(SCOPE),"authorization_response_iss_parameter_supported",true);}
    void configured(){if(base==null||tenant==null||client==null||secret==null||owner==null)throw new IllegalStateException("Microsoft authentication is not configured");}
    Reply register(JsonNode input){
        List<String>redirects=strings(input.path("redirect_uris"));
        if(redirects.isEmpty()||redirects.size()>4||redirects.stream().anyMatch(s->!allowedRedirect(s)))return error("invalid_redirect_uri");
        if(input.has("token_endpoint_auth_method")&&!text(input,"token_endpoint_auth_method").equals("none"))return error("invalid_client_metadata");
        String id=random();ObjectNode c=obj("client_id",id,"client_name","NHL ChatGPT","redirect_uris",redirects,"token_endpoint_auth_method","none","grant_types",List.of("authorization_code","refresh_token"),"response_types",List.of("code"));
        // Registrations are capped by date to keep unauthenticated DCR storage bounded.
        String day=LocalDate.now(ZoneOffset.UTC).toString();
        return store.locked("registration/"+day,()->{ObjectNode counter=store.get("oauth/count/"+day);int n=counter==null?0:counter.path("count").asInt();if(n>=30)return new Reply(429,obj("error","slow_down"),null);store.put("oauth/count/"+day,obj("count",n+1));store.create("oauth/clients/"+id,c);return new Reply(201,c,null);});
    }
    Reply authorize(Map<String,String>q){
        configured();String id=q.getOrDefault("client_id","");if(!id.matches("[A-Za-z0-9_-]{43}"))return error("invalid_client");
        ObjectNode registration=store.get("oauth/clients/"+id);String redirect=q.get("redirect_uri"),challenge=q.get("code_challenge");
        if(registration==null||!strings(registration.path("redirect_uris")).contains(redirect))return error("invalid_client");
        if(!"code".equals(q.get("response_type"))||!"S256".equals(q.get("code_challenge_method"))||challenge==null||!challenge.matches("[A-Za-z0-9_-]{43}")||!SCOPE.equals(q.get("scope"))||!validResource(q))return error("invalid_request");
        String state=random(),nonce=random(),verifier=random();
        ObjectNode session=obj("client_id",id,"redirect_uri",redirect,"state",q.getOrDefault("state",""),"challenge",challenge,"nonce",nonce,"microsoft_verifier",verifier,"expires",Instant.now().plusSeconds(600).toString());
        store.create("oauth/login/"+hash(state),session);
        String url="https://login.microsoftonline.com/"+tenant+"/oauth2/v2.0/authorize?client_id="+enc(client)+"&response_type=code&redirect_uri="+enc(base+"/oauth/microsoft/callback")+"&response_mode=query&scope=openid%20profile&state="+enc(state)+"&nonce="+enc(nonce)+"&code_challenge="+enc(hash(verifier))+"&code_challenge_method=S256";
        return new Reply(302,null,url);
    }
    Reply callback(Map<String,String>q){
        configured();String state=q.getOrDefault("state","");if(!state.matches("[A-Za-z0-9_-]{43}"))return error("invalid_request");
        ObjectNode session=store.take("oauth/login/"+hash(state));if(!active(session))return error("invalid_request");
        if(q.containsKey("error"))return redirectError(session,"access_denied");
        try{
            Map<String,String>values=new LinkedHashMap<>();values.put("grant_type","authorization_code");values.put("client_id",client);values.put("client_secret",secret);values.put("code",q.getOrDefault("code",""));values.put("redirect_uri",base+"/oauth/microsoft/callback");values.put("code_verifier",text(session,"microsoft_verifier"));
            var response=Schedule.HTTP.send(HttpRequest.newBuilder(URI.create("https://login.microsoftonline.com/"+tenant+"/oauth2/v2.0/token")).timeout(Duration.ofSeconds(20)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(encode(values))).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)return redirectError(session,"access_denied");
            verifyMicrosoft(text(parse(response.body()),"id_token"),text(session,"nonce"));
            String code=random();store.create("oauth/codes/"+hash(code),obj("client_id",text(session,"client_id"),"redirect_uri",text(session,"redirect_uri"),"challenge",text(session,"challenge"),"expires",Instant.now().plusSeconds(120).toString()));
            return new Reply(302,null,redirect(text(session,"redirect_uri"),Map.of("code",code,"state",text(session,"state"),"iss",base)));
        }catch(InterruptedException e){Thread.currentThread().interrupt();return redirectError(session,"server_error");}catch(Exception e){return redirectError(session,"access_denied");}
    }
    void verifyMicrosoft(String token,String nonce)throws Exception{
        SignedJWT jwt=SignedJWT.parse(token);if(!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()))throw new SecurityException("Unexpected algorithm");
        if(microsoftKeys==null||Instant.now().isAfter(keysExpire)||microsoftKeys.getKeyByKeyId(jwt.getHeader().getKeyID())==null){
            var response=Schedule.HTTP.send(HttpRequest.newBuilder(URI.create("https://login.microsoftonline.com/"+tenant+"/discovery/v2.0/keys")).timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new SecurityException("Signing keys unavailable");microsoftKeys=JWKSet.parse(response.body());keysExpire=Instant.now().plusSeconds(3600);
        }
        JWK key=microsoftKeys.getKeyByKeyId(jwt.getHeader().getKeyID());if(!(key instanceof RSAKey rsa)||!jwt.verify(new RSASSAVerifier(rsa)))throw new SecurityException("Invalid signature");
        JWTClaimsSet c=jwt.getJWTClaimsSet();Instant now=Instant.now();
        if(!c.getIssuer().equals("https://login.microsoftonline.com/"+tenant+"/v2.0")||!c.getAudience().contains(client)||c.getExpirationTime()==null||c.getExpirationTime().toInstant().isBefore(now)||c.getIssueTime()==null||c.getIssueTime().toInstant().isAfter(now.plusSeconds(60))||(c.getNotBeforeTime()!=null&&c.getNotBeforeTime().toInstant().isAfter(now.plusSeconds(60)))||!owner.equals(c.getStringClaim("oid"))||!tenant.equals(c.getStringClaim("tid"))||!nonce.equals(c.getStringClaim("nonce")))throw new SecurityException("Invalid identity");
    }
    Reply token(Map<String,String>q){
        if(!validResource(q))return error("invalid_target");String id=q.getOrDefault("client_id","");if(!id.matches("[A-Za-z0-9_-]{43}"))return error("invalid_client");
        String grant=q.getOrDefault("grant_type","");
        if(grant.equals("authorization_code")){
            String code=q.getOrDefault("code","");if(!code.matches("[A-Za-z0-9_-]{43}"))return error("invalid_grant");
            // Consume a code even after an invalid verifier: authorization codes are strictly single-use.
            ObjectNode c=store.take("oauth/codes/"+hash(code));String verifier=q.getOrDefault("code_verifier","");
            if(!active(c)||!text(c,"client_id").equals(id)||!text(c,"redirect_uri").equals(q.get("redirect_uri"))||!verifier.matches("[A-Za-z0-9._~-]{43,128}")||!MessageDigest.isEqual(text(c,"challenge").getBytes(StandardCharsets.UTF_8),hash(verifier).getBytes(StandardCharsets.UTF_8)))return error("invalid_grant");
        }else if(grant.equals("refresh_token")){
            String refresh=q.getOrDefault("refresh_token","");if(!refresh.matches("[A-Za-z0-9_-]{43}"))return error("invalid_grant");
            ObjectNode r=store.take("oauth/refresh/"+hash(refresh));if(!active(r)||!text(r,"client_id").equals(id))return error("invalid_grant");
        }else return error("unsupported_grant_type");
        String access=random(),refresh=random();
        store.create("oauth/access/"+hash(access),obj("client_id",id,"resource",base+"/mcp","scope",SCOPE,"expires",Instant.now().plusSeconds(3600).toString()));
        store.create("oauth/refresh/"+hash(refresh),obj("client_id",id,"expires",Instant.now().plus(Duration.ofDays(30)).toString()));
        return new Reply(200,obj("access_token",access,"token_type","Bearer","expires_in",3600,"refresh_token",refresh,"scope",SCOPE),null);
    }
    boolean validResource(Map<String,String>q){return (base+"/mcp").equals(q.get("resource"));}
    boolean authenticated(String authorization){
        if(authorization==null||!authorization.matches("Bearer [A-Za-z0-9_-]{43}"))return false;
        ObjectNode t=store.get("oauth/access/"+hash(authorization.substring(7)));
        return active(t)&&text(t,"resource").equals(base+"/mcp")&&text(t,"scope").equals(SCOPE);
    }
    static boolean active(JsonNode n){return n!=null&&n.has("expires")&&Checks.time(text(n,"expires")).isAfter(Instant.now());}
    Reply error(String error){return new Reply(400,obj("error",error),null);}
    Reply redirectError(JsonNode session,String error){return new Reply(302,null,redirect(text(session,"redirect_uri"),Map.of("error",error,"state",text(session,"state"),"iss",base)));}
    static String encode(Map<String,String>values){return values.entrySet().stream().map(e->enc(e.getKey())+"="+enc(e.getValue())).collect(java.util.stream.Collectors.joining("&"));}
    static String redirect(String uri,Map<String,String>q){return uri+(uri.contains("?")?"&":"?")+encode(q);}
    void cleanup(){for(String prefix:List.of("oauth/login/","oauth/codes/","oauth/access/","oauth/refresh/"))for(String key:store.keys(prefix)){ObjectNode n=store.get(key);if(!active(n))store.take(key);}}
}
