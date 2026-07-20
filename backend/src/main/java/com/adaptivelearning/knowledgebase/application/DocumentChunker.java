package com.adaptivelearning.knowledgebase.application;
import org.springframework.stereotype.Component;import java.util.*;
@Component public class DocumentChunker {
 public List<String> chunk(String raw,int target,int max){String text=raw.replace("\u0000","").replaceAll("[\\t ]+"," ").replaceAll("\\n{3,}","\n\n").trim();if(text.isEmpty())return List.of();List<String>out=new ArrayList<>();int start=0;while(start<text.length()){int end=Math.min(text.length(),start+target);if(end<text.length()){int boundary=Math.max(text.lastIndexOf("\n\n",end),Math.max(text.lastIndexOf('。',end),text.lastIndexOf('.',end)));if(boundary>start+target/2)end=boundary+1;}end=Math.min(end,Math.min(text.length(),start+max));String value=text.substring(start,end).trim();if(!value.isEmpty())out.add(value);if(end>=text.length())break;int overlap=Math.min(100,Math.max(20,(end-start)/10));start=Math.max(start+1,end-overlap);}return out;}
}
