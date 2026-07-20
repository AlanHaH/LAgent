package com.adaptivelearning.knowledgebase.application;

import org.springframework.stereotype.Component;import java.util.*;import java.util.regex.Pattern;
@Component public class TextVectorizer {
 private static final int DIM=128;private static final Pattern WORD=Pattern.compile("[a-z0-9_]+",Pattern.CASE_INSENSITIVE);
 public double[] vector(String text){double[]v=new double[DIM];for(String token:tokens(text)){int h=token.hashCode();int idx=Math.floorMod(h,DIM);v[idx]+=((h&1)==0?1:-1);}double norm=0;for(double x:v)norm+=x*x;norm=Math.sqrt(norm);if(norm>0)for(int i=0;i<v.length;i++)v[i]/=norm;return v;}
 public Set<String> tokens(String text){String lower=text==null?"":text.toLowerCase(Locale.ROOT);Set<String>out=new LinkedHashSet<>();var m=WORD.matcher(lower);while(m.find())out.add(m.group());for(int i=0;i<lower.length();i++){char c=lower.charAt(i);if(Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)out.add(String.valueOf(c));if(i+1<lower.length()&&Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN&&Character.UnicodeScript.of(lower.charAt(i+1))==Character.UnicodeScript.HAN)out.add(lower.substring(i,i+2));}return out;}
 public double cosine(double[]a,double[]b){double s=0;for(int i=0;i<Math.min(a.length,b.length);i++)s+=a[i]*b[i];return s;}
 public double keyword(String query,String text){Set<String>q=tokens(query);if(q.isEmpty())return 0;Set<String>t=tokens(text);long hit=q.stream().filter(t::contains).count();return(double)hit/q.size();}
}
