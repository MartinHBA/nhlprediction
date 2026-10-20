package sk.nhl;

import java.util.*;

/** RFC 4180 fields, including quoted commas/newlines and the identical duplicate team header in MoneyPuck exports. */
final class Csv {
    static List<Map<String,String>> read(String text) {
        List<List<String>> records=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder field=new StringBuilder();boolean quoted=false;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);if(i==0&&c=='\ufeff')continue;
            if(c=='"'){if(quoted&&i+1<text.length()&&text.charAt(i+1)=='"'){field.append('"');i++;}else if(quoted||field.isEmpty())quoted=!quoted;else throw new IllegalArgumentException("Unexpected CSV quote");}
            else if(c==','&&!quoted){row.add(field.toString());field.setLength(0);}
            else if((c=='\n'||c=='\r')&&!quoted){if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;row.add(field.toString());field.setLength(0);if(row.size()>1||!row.get(0).isBlank())records.add(row);row=new ArrayList<>();}
            else field.append(c);
        }
        if(quoted)throw new IllegalArgumentException("Unclosed CSV field");
        if(!row.isEmpty()||!field.isEmpty()){row.add(field.toString());records.add(row);}
        if(records.isEmpty())throw new IllegalArgumentException("Empty CSV");List<String> header=records.remove(0).stream().map(s->s.trim().toLowerCase(Locale.ROOT)).toList();
        List<Map<String,String>> result=new ArrayList<>();
        for(List<String> values:records){if(values.size()!=header.size())throw new IllegalArgumentException("CSV row width differs from header");Map<String,String> m=new HashMap<>();for(int i=0;i<header.size();i++){String old=m.put(header.get(i),values.get(i));if(old!=null&&!old.equals(values.get(i)))throw new IllegalArgumentException("Conflicting duplicate CSV header");}result.add(m);}
        return result;
    }
}
