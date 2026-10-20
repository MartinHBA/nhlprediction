package sk.nhl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static sk.nhl.Json.*;

final class Engine {
    record Scores(int[] rh,int[] ra,int[] fh,int[] fa) {}
    record Projection(double home,double away,ObjectNode details) {}
    static double round(double x,int digits) {double p=Math.pow(10,digits);return Math.round(x*p)/p;}
    static double clip(double x,double low,double high){return Math.max(low,Math.min(high,x));}
    static double blend(double prior,double current,double games){double w=games/(games+25);return w*current+(1-w)*prior;}
    static double goalie(JsonNode g) {
        double starts=number(g,"starts_current"),priorStarts=number(g,"starts_prior");
        double prior=priorStarts>0?number(g,"gsax_per60_prior"):0;
        double current=starts>0&&!missing(g.path("gsax_per60_current"))?number(g,"gsax_per60_current"):prior;
        prior=prior*priorStarts/(priorStarts+25);double w=starts/(starts+25);
        return clip((w*current+(1-w)*prior)*.6,-.5,.5);
    }
    static Map<String,Double> rates(JsonNode t){Map<String,Double>r=new HashMap<>();for(String f:Checks.RATE)if(!f.equals("games"))r.put(f,blend(number(t,"prior."+f),number(t,"current."+f),number(t,"current.games")));return r;}
    static Projection project(JsonNode g) {
        JsonNode h=g.path("home"),a=g.path("away");var hr=rates(h);var ar=rates(a);
        double hp=clip(hr.get("pp_drawn_per_game")+ar.get("penalties_taken_per_game"),0,10),ap=clip(ar.get("pp_drawn_per_game")+hr.get("penalties_taken_per_game"),0,10),even=Math.max(30,58-hp-ap);
        double rest=clip(number(h,"context.rest_days")-number(a,"context.rest_days"),-3,3)*.045;
        double travel=(number(a,"context.travel_timezones")-number(h,"context.travel_timezones"))*.025;
        double gh=goalie(h.path("goalie")),ga=goalie(a.path("goalie"));
        double lh=.5*(hr.get("xgf_5v5_per60")+ar.get("xga_5v5_per60"))*even/60+.5*(hr.get("pp_xgf_per60")+ar.get("pk_xga_per60"))*hp/60+number(h,"roster.attack_delta_goals")+number(a,"roster.defense_delta_goals_allowed")-ga+.1+rest/2+travel/2;
        double la=.5*(ar.get("xgf_5v5_per60")+hr.get("xga_5v5_per60"))*even/60+.5*(ar.get("pp_xgf_per60")+hr.get("pk_xga_per60"))*ap/60+number(a,"roster.attack_delta_goals")+number(h,"roster.defense_delta_goals_allowed")-gh-.1-rest/2-travel/2;
        ArrayNode warnings=arr();
        for(String s:List.of("home","away")){
            JsonNode t=g.path(s);
            if(!t.path("roster").path("quantified").asBoolean())warnings.add(s.toUpperCase()+"_LINEUP_DELTAS_UNKNOWN_NEUTRAL_BASELINE_WITH_WIDER_SENSITIVITY");
            if(missing(at(t,"goalie.gsax_per60_prior"))&&missing(at(t,"goalie.gsax_per60_current")))warnings.add(s.toUpperCase()+"_GOALIE_GSAX_UNKNOWN_NEUTRAL_BASELINE_ASSUMPTION");
        }
        if(Math.min(lh,la)<.4||Math.max(lh,la)>7.5)warnings.add("PROJECTED_LAMBDA_OUTSIDE_PLAUSIBLE_RANGE; manual review required");
        return new Projection(clip(lh,.4,7.5),clip(la,.4,7.5),obj("even_minutes",even,"home_pp_minutes",hp,"away_pp_minutes",ap,"home_goalie_adjustment",gh,"away_goalie_adjustment",ga,"warnings",warnings));
    }
    static int poisson(double lambda,SplittableRandom r){double limit=Math.exp(-lambda),p=1;int n=0;do{n++;p*=r.nextDouble();}while(p>limit);return n-1;}
    static Scores simulate(double lh,double la,int n,long seed){
        SplittableRandom r=new SplittableRandom(seed);int[]rh=new int[n],ra=new int[n],fh=new int[n],fa=new int[n];
        double pot=clip(.5+.28*(lh-la)/Math.max(lh+la,.01),.25,.75);
        for(int i=0;i<n;i++){
            int h=poisson(lh*58/60,r),a=poisson(la*58/60,r);
            boolean hp=a>h&&a-h<=2&&r.nextDouble()<(a-h==1?.7:.4),ap=h>a&&h-a<=2&&r.nextDouble()<(h-a==1?.7:.4);
            rh[i]=h+poisson(lh*2/60*(hp?1.6:ap?3.5:1),r);ra[i]=a+poisson(la*2/60*(ap?1.6:hp?3.5:1),r);
            fh[i]=rh[i];fa[i]=ra[i];if(rh[i]==ra[i]){if(r.nextDouble()<pot)fh[i]++;else fa[i]++;}
        }
        return new Scores(rh,ra,fh,fa);
    }
    static double[] settle(JsonNode m,Scores sim){
        boolean reg=text(m,"score_basis").equals("regulation");int[]h=reg?sim.rh:sim.fh,a=reg?sim.ra:sim.fa;
        String k=text(m,"kind"),sel=text(m,"selection");double line=number(m,"line");int wins=0,push=0;
        for(int i=0;i<h.length;i++){
            double outcome=switch(k){
                case "total","team_total"->{int v=k.equals("total")?h[i]+a[i]:text(m,"team").equals("home")?h[i]:a[i];yield sel.equals("over")?v-line:line-v;}
                case "handicap"->(sel.equals("home")?h[i]-a[i]:a[i]-h[i])+line;
                case "moneyline"->(sel.equals("home")?h[i]>a[i]:a[i]>h[i])?1:-1;
                case "regulation_1x2"->(sel.equals("home")?h[i]>a[i]:sel.equals("away")?a[i]>h[i]:h[i]==a[i])?1:-1;
                case "double_chance"->(sel.equals("1X")?h[i]>=a[i]:sel.equals("X2")?a[i]>=h[i]:h[i]!=a[i])?1:-1;
                default->throw new IllegalArgumentException("Unsupported market");
            };
            if(outcome>0)wins++;else if(outcome==0)push++;
        }
        return new double[]{(double)wins/h.length,(double)push/h.length,(double)(h.length-wins-push)/h.length};
    }
    static double ev(double[]p,double odds){return p[0]*(odds-1)-p[2];}
    static ObjectNode evaluate(JsonNode g,String asOf,int n,long seed){
        Projection p=project(g);Scores sim=simulate(p.home,p.away,n,seed);boolean quantified=true,confirmed=true,sources=true;
        for(String s:List.of("home","away")){
            quantified&=at(g,s+".roster.quantified").asBoolean();confirmed&=text(g,s+".goalie.status").equals("confirmed");
            for(String group:List.of("prior","current","goalie","roster"))sources&=text(g,s+"."+group+".source.status").equals("verified");
        }
        double f=quantified?.12:.18;List<Scores> scenarios=new ArrayList<>();int index=1;
        for(double[]factor:List.of(new double[]{1-f,1-f},new double[]{1+f,1+f},new double[]{1-f,1+f},new double[]{1+f,1-f}))scenarios.add(simulate(p.home*factor[0],p.away*factor[1],Math.max(10000,n/3),seed+index++));
        ArrayNode markets=arr();
        for(JsonNode m:g.path("markets")){
            double[]prob=settle(m,sim);double value=ev(prob,number(m,"odds")),min=value,max=value;
            double minimumProbability=prob[0],maximumProbability=prob[0],minimumOdds=prob[0]>0?(1-prob[1])/prob[0]:Double.POSITIVE_INFINITY;
            for(Scores s:scenarios){double[] scenario=settle(m,s);double v=ev(scenario,number(m,"odds"));min=Math.min(min,v);max=Math.max(max,v);minimumProbability=Math.min(minimumProbability,scenario[0]);maximumProbability=Math.max(maximumProbability,scenario[0]);minimumOdds=Math.max(minimumOdds,scenario[0]>0?(1-scenario[1])/scenario[0]:Double.POSITIVE_INFINITY);}
            ArrayNode issues=arr(),warnings=arr();String kind=text(m,"kind"),sel=text(m,"selection");
            if(kind.equals("team_total")&&sel.equals("over")&&number(m,"line")==2.5)issues.add("SUSPENDED_TEAM_OVER_2_5");
            if(!confirmed)issues.add("GOALIE_NOT_CONFIRMED");if(!quantified)warnings.add("LINEUP_IMPACT_UNQUANTIFIED; sensitivity widened");
            if(!sources)issues.add("UNVERIFIED_TEAM_INPUT");if(!text(m,"source.status").equals("verified"))issues.add("UNVERIFIED_ODDS");
            if(!m.path("settlement_verified").asBoolean())issues.add("MARKET_SETTLEMENT_NOT_VERIFIED");
            var cutoff=Checks.time(asOf);
            if(java.time.Duration.between(Checks.time(text(m,"source.checked_at")),cutoff).toSeconds()>7200)issues.add("ODDS_STALE_OVER_2_HOURS");
            for(String side:List.of("home","away")){
                for(String group:List.of("goalie","roster"))if(java.time.Duration.between(Checks.time(text(g,side+"."+group+".source.checked_at")),cutoff).toSeconds()>21600)issues.add(side.toUpperCase()+"_"+group.toUpperCase()+"_STALE_OVER_6_HOURS");
                if(number(g,side+".goalie.starts_current")+number(g,side+".goalie.starts_prior")<10)issues.add(side.toUpperCase()+"_GOALIE_INSUFFICIENT_HISTORY");
            }
            if(java.time.Duration.between(cutoff,Checks.time(text(g,"starts_at"))).toSeconds()>28800)issues.add("FINAL_LINEUP_WINDOW_NOT_REACHED");
            if(number(m,"odds")>1.3)issues.add("ABOVE_PREFERRED_SINGLE_ODDS_CAP");if(min<=0)issues.add("NO_CONSERVATIVE_EDGE_IN_SCENARIOS");
            if(strings(p.details.path("warnings")).stream().anyMatch(w->w.startsWith("PROJECTED_LAMBDA_OUTSIDE_PLAUSIBLE_RANGE")))issues.add("LAMBDA_OUT_OF_RANGE");
            markets.add(obj("id",text(m,"id"),"kind",kind,"selection",sel,"line",m.path("line"),"team",m.path("team"),"score_basis",text(m,"score_basis"),"odds",number(m,"odds"),"p_win",round(prob[0],5),"p_push",round(prob[1],5),"p_loss",round(prob[2],5),"p_win_min",round(minimumProbability,5),"p_win_max",round(maximumProbability,5),"minimum_odds_conservative",Double.isFinite(minimumOdds)?round(minimumOdds,5):null,"p_break_even_no_push",round(1/number(m,"odds"),5),"ev",round(value,5),"scenario_ev_min",round(min,5),"scenario_ev_max",round(max,5),"issues",issues,"warnings",warnings,"research_eligible",issues.isEmpty()));
        }
        int hw=0,draw=0,regHome=0;for(int i=0;i<n;i++){if(sim.fh[i]>sim.fa[i])hw++;if(sim.rh[i]==sim.ra[i])draw++;if(sim.rh[i]>sim.ra[i])regHome++;}
        ArrayNode estimates=AlternativeTotals.estimate(g,sim,scenarios,asOf);
        ArrayNode handicapEstimates=AlternativeHandicaps.estimate(g,sim,scenarios,asOf),allEstimates=estimates.deepCopy();allEstimates.addAll(handicapEstimates);
        ObjectNode recommendation=Recommendations.select(g,markets,allEstimates,p.details,asOf);
        return obj("game_id",text(g,"game_id"),"matchup",text(g,"away.name")+" @ "+text(g,"home.name"),"starts_at",text(g,"starts_at"),"status",text(g,"status"),"lambda_home",round(p.home,4),"lambda_away",round(p.away,4),"lambda_total",round(p.home+p.away,4),"projection_details",p.details,"goalies_confirmed",confirmed,"lineups_quantified",quantified,"p_home_win",round((double)hw/n,5),"p_away_win",round((double)(n-hw)/n,5),"p_regulation_home",round((double)regHome/n,5),"p_regulation_draw",round((double)draw/n,5),"markets",markets,"estimated_totals",estimates,"estimated_handicaps",handicapEstimates,"recommendation",recommendation,"simulations",n);
    }
    record Combo(List<JsonNode>legs,double odds,double prob){double rank(){return odds*prob-1-.008*Math.max(0,legs.size()-1);}}
    static ObjectNode combo(JsonNode games){
        List<Combo>beam=new ArrayList<>(List.of(new Combo(List.of(),1,1)));
        for(JsonNode g:games){
            if(!text(g,"status").equals("scheduled"))continue;
            List<JsonNode>options=new ArrayList<>();for(JsonNode m:g.path("markets"))if(m.path("research_eligible").asBoolean()&&number(m,"p_push")<1e-5){ObjectNode leg=m.deepCopy();leg.put("game_id",text(g,"game_id"));leg.put("matchup",text(g,"matchup"));options.add(leg);}
            options.sort(Comparator.comparingDouble((JsonNode m)->number(m,"scenario_ev_min")).reversed());if(options.size()>3)options=options.subList(0,3);
            List<Combo>next=new ArrayList<>(beam);for(Combo c:beam)if(c.legs.size()<6)for(JsonNode m:options){double odds=c.odds*number(m,"odds");if(odds>4+1e-9)continue;List<JsonNode>legs=new ArrayList<>(c.legs);legs.add(m);next.add(new Combo(legs,odds,c.prob*Math.max(0,(number(m,"scenario_ev_min")+1)/number(m,"odds"))));}
            next.sort(Comparator.comparingDouble(Combo::rank).reversed());beam=new ArrayList<>(next.subList(0,Math.min(800,next.size())));
        }
        var best=beam.stream().filter(c->!c.legs.isEmpty()&&c.odds*c.prob>1).max(Comparator.comparingDouble(Combo::rank));
        return best.map(c->obj("legs",c.legs,"odds",round(c.odds,5),"conservative_joint_ev",round(c.odds*c.prob-1,5))).orElseGet(()->obj("legs",arr(),"odds",null,"conservative_joint_ev",null));
    }
    static ObjectNode run(JsonNode d,int n,long seed,boolean research){
        if(n<1000||n>250000)throw new IllegalArgumentException("sims must be 1000..250000");
        var errors=Checks.validate(d);if(!errors.isEmpty())return obj("status","INVALID_INPUT","errors",errors,"games",arr(),"ticket",null);
        ObjectNode checks=Checks.audit(d);ArrayNode results=arr();int i=0,confirmed=0,unconfirmed=0,marketCount=0,eligible=0,blocked=0;boolean missing=false;
        for(JsonNode g:d.path("games")){
            JsonNode rep=checks.path("games").get(i);int gameIndex=i++;
            for(String side:List.of("home","away")){if(text(g,side+".goalie.status").equals("confirmed"))confirmed++;else unconfirmed++;}
            ObjectNode result;
            if(!text(g,"status").equals("scheduled"))result=obj("game_id",text(g,"game_id"),"matchup",text(g,"away.name")+" @ "+text(g,"home.name"),"starts_at",text(g,"starts_at"),"status",text(g,"status"),"markets",arr(),"skipped","ALREADY_STARTED_OR_FINAL");
            else if(!rep.path("model_missing").isEmpty()){missing=true;result=obj("game_id",text(g,"game_id"),"matchup",text(g,"away.name")+" @ "+text(g,"home.name"),"starts_at",text(g,"starts_at"),"status",text(g,"status"),"markets",arr(),"skipped","CRITICAL_MODEL_INPUTS_MISSING","missing",rep.path("model_missing"));}
            else {
                result=evaluate(g,text(d,"as_of"),n,seed+gameIndex*200);
                for(JsonNode m:result.path("markets")){
                    if(!text(rep,"status").equals("READY_FOR_MODEL")){((ArrayNode)m.path("issues")).add("CHECKLIST_INCOMPLETE");((ObjectNode)m).put("research_eligible",false);}
                    marketCount++;if(m.path("research_eligible").asBoolean())eligible++;if(strings(m.path("issues")).contains("SUSPENDED_TEAM_OVER_2_5"))blocked++;
                }
                if(!text(rep,"status").equals("READY_FOR_MODEL"))result.putNull("recommendation");
            }
            results.add(result);
        }
        boolean complete=checks.path("complete").asBoolean();ObjectNode combo=complete?combo(results):obj("legs",arr(),"odds",null,"conservative_joint_ev",null);
        String status=!complete?"CHECKLIST_INCOMPLETE":missing?"INSUFFICIENT_DATA":d.path("synthetic_data").asBoolean()?"SYNTHETIC_DEMO_ONLY":!combo.path("legs").isEmpty()?"CANDIDATE_REVIEW_REQUIRED":"NO_BET";
        return obj("engine_version","2.1.0-java","as_of",text(d,"as_of"),"status",status,"model_note","Heuristic uncalibrated baseline; probabilities are provisional estimates.","checklist",checks,"audit",obj("scheduled_game_count",d.path("expected_game_ids").size(),"processed_game_count",results.size(),"confirmed_goalie_count",confirmed,"unconfirmed_goalie_count",unconfirmed,"market_count",marketCount,"research_eligible_market_count",eligible,"blocked_team_over_2_5",blocked),"games",results,"ticket",status.equals("CANDIDATE_REVIEW_REQUIRED")?combo:null,"candidate_preview_not_validated",research?combo:null);
    }
}
