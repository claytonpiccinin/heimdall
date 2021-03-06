/*-
 * =========================LICENSE_START==================================
 * heimdall-gateway
 * ========================================================================
 * Copyright (C) 2018 Conductor Tecnologia SA
 * ========================================================================
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ==========================LICENSE_END===================================
 */
package br.com.conductor.heimdall.gateway.trace;

import br.com.conductor.heimdall.core.exception.ExceptionMessage;
import br.com.conductor.heimdall.core.exception.HeimdallException;
import br.com.conductor.heimdall.core.util.LocalDateTimeSerializer;
import br.com.conductor.heimdall.core.util.UrlUtil;
import br.com.conductor.heimdall.middleware.spec.StackTrace;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.*;

import static net.logstash.logback.marker.Markers.append;

/**
 * Represents the trace message.
 *
 * @author Thiago Sampaio
 *
 */
@Data
@Slf4j
public class Trace {
	
	 private static final Logger logMongo = LoggerFactory.getLogger("mongo");
	 
	 private static final Logger logstash = LoggerFactory.getLogger("logstash");

     private String method;

     private String url;

     private int resultStatus;

     @JsonIgnore
     private Long initialTime;

     private Long durationMillis;

     @JsonSerialize(using = LocalDateTimeSerializer.class)
     private LocalDateTime insertedOnDate = LocalDateTime.now();

     private Long apiId;

     private String apiName;

     private String app;

     private String accessToken;

     private String receivedFromAddress;

     private String clientId;

     private Long resourceId;

     private String appDeveloper;
     
     private Long operationId;
     
     private RequestResponseParser request;
     
     private RequestResponseParser response;

     private String pattern;

     private Boolean cache;
     
     @JsonInclude(Include.NON_NULL)
     private StackTrace stackTrace;

     @Getter
     private List<GeneralTrace> traces = new ArrayList<>();
     
     @Getter
     private Map<String, FilterDetail> filters = new LinkedHashMap<>();

     private String profile;
     
     @JsonIgnore
     private boolean printAllTrace;
     @JsonIgnore
     private boolean printMongo;

     @JsonIgnore
     private boolean shouldPrint;
     
     @JsonIgnore
     private boolean printLogstash;

     private String version;
     
     public Trace() {
    	 
     }

     /**
      * Create a Trace.
      * @param printAllTrace
      * @param profile
      * @param servletRequest
      * @param printMongo
      * @param printLogstash
      */
     public Trace(boolean printAllTrace, String profile, ServletRequest servletRequest, boolean printMongo, boolean printLogstash){

          this.shouldPrint = true;
          this.profile = profile;
          this.printAllTrace = printAllTrace;
          this.printMongo = printMongo;
          this.printLogstash = printLogstash;
          HttpServletRequest request = (HttpServletRequest) servletRequest;
          HeimdallException.checkThrow(request == null, ExceptionMessage.GLOBAL_REQUEST_NOT_FOUND);

          setInitialTime(System.currentTimeMillis());
          setMethod(request.getMethod());
          setUrl(UrlUtil.getCurrentUrl(request));

          Enumeration<String> headers = request.getHeaders("x-forwarded-for");
          if (headers != null) {

               List<String> listIps = new ArrayList<>();
               while (headers.hasMoreElements()) {
                    String ip = headers.nextElement();
                    listIps.add(ip);
               }

               setReceivedFromAddress(String.join(",", listIps));

          }

     }
     
     /**
      * Create a Trace.
      * 
      * @param printAllTrace
      * @param profile
      * @param servletRequest
      * @param printMongo
      * @param printLogstash
      * @param version
      */
     public Trace(boolean printAllTrace, String profile, ServletRequest servletRequest, boolean printMongo, boolean printLogstash, String version) {
    	 this(printAllTrace, profile, servletRequest, printMongo, printLogstash);
    	 this.version = version;
     }

	/**
      * Adds a {@link FilterDetail} to the List.
      * 
      * @param detail {@link FilterDetail}
      */
     public void addFilter(String name, FilterDetail detail) {
          filters.put(name, detail);
     }

     /**
      * Creates and adds a new trace to the traces List.
      * 
      * @param msg	Message to be added to the trace
      * @return		{@link Trace} created
      */
     public Trace trace(String msg) {

          traces.add(new GeneralTrace(msg));

          return this;

     }

     /**
      * Creates and adds a new trace from message and Object.
      * 
      * @param msg		The message for the trace
      * @param object	The Object to be added to the trace
      * @return			{@link Trace} created
      */
     public Trace trace(String msg, Object object) {

          traces.add(new GeneralTrace(msg, object));

          return this;

     }

     /**
      * Writes a {@link HttpServletResponse} to the Heimdall Trace
      * 
      * @param response	{@link HttpServletResponse}
      */
     public void write(HttpServletResponse response) {

          try {

               setResultStatus(response.getStatus());
               setDurationMillis(System.currentTimeMillis() - getInitialTime());
               
               prepareLog(response.getStatus());

          } catch (Exception e) {

               log.error(e.getMessage(), e);

          } finally {

               TraceContextHolder.getInstance().clearActual();
          }

     }

     /*
      * Heimdall uses three levels of log depending on the status code of the response.
      *
      * Levels per status code range:
      *   * 1xx~2xx = INFO
      *   * 3xx~4xx = WARN
      *   * OTHER   = ERROR
      */
     private void prepareLog(Integer statusCode) throws JsonProcessingException {

          String url = Objects.nonNull(getUrl()) ? getUrl() : "";
          ObjectMapper mapper = new ObjectMapper();

          if (printAllTrace) {

               if (isInfo(statusCode)) {

                    log.info(" [HEIMDALL-TRACE] - {} ", mapper.writeValueAsString(this));
               } else if (isWarn(statusCode)) {

                    log.warn(" [HEIMDALL-TRACE] - {} ", mapper.writeValueAsString(this));
               } else {

                    log.error(" [HEIMDALL-TRACE] - {} ", mapper.writeValueAsString(this));
               }
          } else {
               if (isInfo(statusCode)) {

                    log.info(append("call", this), " [HEIMDALL-TRACE] - " + url);
               } else if (isWarn(statusCode)) {

                    log.warn(append("call", this), " [HEIMDALL-TRACE] - " + url);
               } else {

                    log.error(append("call", this), " [HEIMDALL-TRACE] - " + url);
               }
          }

          if (printMongo) {
               printInLogger(logMongo, statusCode);
          }

          if (printLogstash) {
        	  printInLogger(logstash, statusCode);
          }
     }

     private void printInLogger(Logger logger, Integer statusCode) throws JsonProcessingException {
          ObjectMapper mapper = new ObjectMapper();

          if (isInfo(statusCode)) {

               logger.info(mapper.writeValueAsString(this));
          } else if (isWarn(statusCode)) {

               logger.warn(mapper.writeValueAsString(this));
          } else {

               logger.error(mapper.writeValueAsString(this));
          }
     }

     /*
      * Checks if the status code is in range 1xx to 2xx
      */
     private static boolean isInfo(Integer statusCode) {
          return HttpStatus.valueOf(statusCode).is1xxInformational() ||
                  HttpStatus.valueOf(statusCode).is2xxSuccessful();
     }

     /*
      * Checks if the status code is in range 3xx to 4xx
      */
     private static boolean isWarn(Integer statusCode) {
          return HttpStatus.valueOf(statusCode).is3xxRedirection() ||
                  HttpStatus.valueOf(statusCode).is4xxClientError();
     }

}
