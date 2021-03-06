package org.aksw.simba.lsq.util;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.aksw.jena_sparql_api.utils.model.ResourceUtils;
import org.aksw.simba.lsq.vocab.LSQ;
import org.aksw.simba.lsq.vocab.PROV;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.jena.rdf.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebLogParser {

    private static final Logger logger = LoggerFactory
            .getLogger(WebLogParser.class);

    private static Map<String, WebLogParser> formatRegistry;
    
    public static Map<String, WebLogParser> getFormatRegistry() {
        if(formatRegistry == null) {
            formatRegistry = new HashMap<>();
            
            formatRegistry.put("apache", new WebLogParser(apacheLogEntryPattern, apacheDateFormat));
            formatRegistry.put("virtuoso", new WebLogParser(virtuosoLogEntryPattern, virtuosoDateFormat));
        }
        
        return formatRegistry;
    }
    

    //9c6a991dbf3332fdc973c5b8461ba79f [30/Apr/2010 00:00:00 -0600] "R" "/sparql?default-graph-uri=http%3A%2F%2Fdbpedia.org&should-sponge=&query=SELECT+DISTINCT+%3Fcity+%3Flatd%0D%0AFROM+%3Chttp%3A%2F%2Fdbpedia.org%3E%0D%0AWHERE+%7B%0D%0A+%3Fcity+%3Chttp%3A%2F%2Fdbpedia.org%2Fproperty%2FsubdivisionName%3E+%3Chttp%3A%2F%2Fdbpedia.org%2Fresource%2FNetherlands%3E.%0D%0A+%3Fcity+%3Chttp%3A%2F%2Fdbpedia.org%2Fproperty%2Flatd%3E+%3Flatd.%0D%0A%7D&format=text%2Fhtml&debug=on&timeout=2200"
    public static String virtuosoLogEntryPatternStr
            = "^"
            + "(?<host>[^\\s]+) "
            + "\\[(?<time>[\\w:/ ]+\\s[+\\-]\\d{4})\\] "
            + "\"(?<unknown>.+?)\" "
            + "\"(?<path>.+?)\""
            ;

    public static String apacheLogEntryPatternStr
            = "^"
            + "(?<host>[^\\s]+) "
            + "(\\S+) "
            + "(?<user>\\S+) "
            + "\\[(?<time>[\\w:/]+\\s[+\\-]\\d{4})\\] "
            //+ "\"(?<request>.+?)\" "
            + "\""
            +  "(?<verb>\\S+)\\s+"
            +  "(?<path>\\S+)\\s+"
            +  "(?<protocol>\\S+)"
            + "\" "
            + "(?<response>\\d{3}) "
            + "(?<bytecount>\\d+) "
            + "\"(?<referer>[^\"]+)\""
            ;
//    String foo = ""
//            + "\"(?<agent>[^\"]*)\""
//            ;

    public static String requestParserStr = "(?<verb>\\S+)\\s+(?<path>\\S+)\\s+(?<protocol>\\S+)";

    public static final Pattern apacheLogEntryPattern = Pattern.compile(apacheLogEntryPatternStr);
    public static final Pattern virtuosoLogEntryPattern = Pattern.compile(virtuosoLogEntryPatternStr);

    public static final Pattern requestParser = Pattern.compile(requestParserStr);

    // 17/Apr/2011:06:47:47 +0200
    public static final DateFormat apacheDateFormat = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z");
    
    // 30/Apr/2010 00:00:00 -0600
    public static final DateFormat virtuosoDateFormat = new SimpleDateFormat("dd/MMM/yyyy HH:mm:ss Z");

/*
    private String hostname;
    private Date date;
    private ApacheLogRequest request;
    private String response;
    private long byteCount;
    private String referer;
    private String userAgent;
*/
    
    protected PatternMatcher patternMatcher;
    protected DateFormat dateFormat;
    
    public WebLogParser(Pattern pattern, DateFormat dateFormat) {
        this(new PatternMatcherImpl(pattern), dateFormat);
    }
    
    public WebLogParser(PatternMatcher patternMatcher, DateFormat dateFormat) {
        this.patternMatcher = patternMatcher;
        this.dateFormat = dateFormat;
    }
    
    public static String encodeUnsafeCharacters(String uri) {
        String result = uri
                .replace("{", "%7B")
                .replace("}", "%7D")
                ;

        return result;
    }

    public void parseEntry(String str, Resource inout) {
        //Matcher m = regexPattern.matcher(str);
        Map<String, String> m = patternMatcher.apply(str);
        
        //List<String> groupNames = Arrays.asList("host", "user", "request", "path", "protocol", "verb"

        if(m != null) {
            ResourceUtils.addLiteral(inout, LSQ.host, m.get("host"));
            ResourceUtils.addLiteral(inout, LSQ.user, m.get("user"));

//            String request = m.get("request");
//            ResourceUtils.addLiteral(inout, LSQ.request, request); 

            // Parse the request part into http verb, path and protocol
//            if(request != null) {
//                Matcher n = requestParser.matcher(request);
//                if(n.find()) {
            String pathStr = m.get("path");

            ResourceUtils.addLiteral(inout, LSQ.protocol, m.get("protocol"));
            ResourceUtils.addLiteral(inout, LSQ.path, pathStr);
            ResourceUtils.addLiteral(inout, LSQ.verb, m.get("veb"));

            if(pathStr != null) {
    
                pathStr = encodeUnsafeCharacters(pathStr);


                // Parse the path and extract sparql query string if present
                String mockUri = "http://example.org" + pathStr;
                try {
                    URI uri = new URI(mockUri);
                    List<NameValuePair> qsArgs = URLEncodedUtils.parse(uri, StandardCharsets.UTF_8.name());
                    String queryStr = qsArgs.stream()
                        .filter(x -> x.getName().equals("query"))
                        .findFirst()
                        .map(x -> x.getValue())
                        .orElse(null);

                    if(queryStr != null) {
                        inout.addLiteral(LSQ.query, queryStr);
                    }
                } catch (Exception e) {
                    //System.out.println(mockUri.substring(244));
                    logger.warn("Could not parse URI: " + mockUri, e);
                }
            }
    
            String timestampStr = m.get("time");
            if(timestampStr != null) {
                Date date;
                try {
                    date = dateFormat.parse(timestampStr);
                    Calendar cal = new GregorianCalendar();
                    cal.setTime(date);
                    inout.addLiteral(PROV.atTime, cal);
                } catch (ParseException e) {
                    inout.addLiteral(LSQ.processingError, "Failed to parse timestamp: " + timestampStr);
                }
            }
        }
    }
}
