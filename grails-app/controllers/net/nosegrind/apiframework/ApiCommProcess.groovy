package net.nosegrind.apiframework


import javax.annotation.Resource
import javax.servlet.http.HttpServletRequest
import org.springframework.web.context.request.ServletRequestAttributes
import javax.servlet.http.HttpSession


import java.text.SimpleDateFormat
import static groovyx.gpars.GParsPool.withPool
import grails.converters.JSON
import grails.converters.XML
import grails.web.servlet.mvc.GrailsParameterMap
import javax.servlet.forward.*
import org.grails.groovy.grails.commons.*
import grails.core.GrailsApplication
import grails.util.Holders
import org.grails.core.artefact.DomainClassArtefactHandler

import org.springframework.beans.factory.annotation.Autowired
import org.grails.plugin.cache.GrailsCacheManager

import javax.servlet.http.HttpServletRequest

import com.google.common.hash.Hashing
import java.nio.charset.StandardCharsets

import org.grails.core.DefaultGrailsDomainClass
import grails.orm.HibernateCriteriaBuilder

/**
 *
 * This abstract provides Common API Methods used by APICommLayer and those that extend it
 * This is simply organizational in keeping repetitively called methods from communication processes
 * for readability
 * @author Owen Rubel
 *
 * @see ApiCommLayer
 *
 */
abstract class ApiCommProcess{

    @Resource
    GrailsApplication grailsApplication

    @Autowired
    GrailsCacheManager grailsCacheManager

    @Autowired
    ThrottleCacheService throttleCacheService

    //@Autowired
    //ApiCacheService apiCacheService = new ApiCacheService()
    List formats = ['text/json','application/json','text/xml','application/xml']
    List optionalParams = ['method','format','contentType','encoding','action','controller','v','apiCombine', 'apiObject','entryPoint','uri','apiObjectVersion']

    int cores = Holders.grailsApplication.config.apitoolkit.procCores as Integer

    boolean batchEnabled = Holders.grailsApplication.config.apitoolkit.batching.enabled
    boolean chainEnabled = Holders.grailsApplication.config.apitoolkit.chaining.enabled


    /**
     * Given the request params, resets parameters for a batch based upon each iteration
     * @see BatchInterceptor#before()
     * @param GrailsParameterMap Map of params created from the request data
     */
    void setBatchParams(GrailsParameterMap params){
        try{
            if (batchEnabled) {
                Object batchVars = request.getAttribute(request.format.toUpperCase())
                if(!request.getAttribute('batchLength')){
                    request.setAttribute('batchLength',request.JSON?.batch.size())
                }
                batchVars['batch'][request.getAttribute('batchInc').toInteger()].each() { k,v ->
                    params."${k}" = v
                }
            }
        }catch(Exception e) {
            throw new Exception("[ApiCommProcess :: setBatchParams] : Exception - full stack trace follows:",e)
        }
    }

    String getModelResponseFormat(){}

    /**
     * Given the request params, resets parameters for an api chain based upon for each iteration
     * @see ChainInterceptor#before()
     * @param GrailsParameterMap Map of params created from the request data
     */
    void setChainParams(GrailsParameterMap params){
        if (chainEnabled) {
            if(!params.apiChain){ params.apiChain = [:] }
            LinkedHashMap chainVars = request.JSON
            if(!request.getAttribute('chainLength')){ request.setAttribute('chainLength',chainVars['chain'].size()) }
            chainVars['chain'].each() { k,v ->
                params.apiChain[k] = v
            }
        }
    }

    /**
     * Returns authorities associated with loggedIn user; creates default authority which will be checked
     * against endpoint 'roles' if no 'loggedIn' user is found
     * @see #checkURIDefinitions(GrailsParameterMap,LinkedHashMap)
     * @see #checkLimit(int)
     * @see ApiCommLayer#handleApiResponse(LinkedHashMap, List, RequestMethod, String, HttpServletResponse, HashMap, GrailsParameterMap)
     * @return a String of the Role of current principal (logged in user)
     */
    String getUserRole() {
        String authority = 'permitAll'
        if (springSecurityService.loggedIn){
            authority = springSecurityService.principal.authorities*.authority[0]
        }
        return authority
    }

    /**
     * Given a deprecationDate, checks the deprecation date against todays date; returns boolean
     * Used mainly to check whether API Version is deprecated
     * @see ApiCommLayer#handleApiRequest(List, String, RequestMethod, HttpServletResponse, GrailsParameterMap)
     * @see ApiCommLayer#handleBatchRequest(List, String, RequestMethod, HttpServletResponse, GrailsParameterMap)
     * @see ApiCommLayer#handleChainRequest(List, String, RequestMethod, HttpServletResponse, GrailsParameterMap)
     * @param String deprecationDate
     * @return a boolean
     */
    boolean checkDeprecationDate(String deprecationDate){
        try{
            Date ddate = new SimpleDateFormat("MM/dd/yyyy").parse(deprecationDate)
            Date deprecated = new Date(ddate.time)
            Date today = new Date()
            if(deprecated < today ) {
                return true
            }
            return false
        }catch(Exception e){
            throw new Exception("[ApiCommProcess :: checkDeprecationDate] : Exception - full stack trace follows:",e)
        }
    }

    /**
     * Given the RequestMethod Object for the request, the endpoint request method and a boolean declaration of whether it is a restAlt(ernative),
     * test To check whether RequestMethod value matches expected request method for endpoint; returns boolean
     * @param RequestMethod request method for httprequest
     * @param String method associated with endpoint
     * @param boolean a boolean value determining if endpoint is 'restAlt' (OPTIONS,TRACE,etc)
     * @return returns true if not endpoint method matches request method
     */
    boolean checkRequestMethod(RequestMethod mthd,String method, boolean restAlt){
        if(!restAlt) {
            return (mthd.getKey() == method) ? true : false
        }
        return true
    }

    // TODO: put in OPTIONAL toggle in application.yml to allow for this check
    /**
     * Given the request params and endpoint request definitions, test to check whether the request params match the expected endpoint params; returns boolean
     * @see ApiFrameworkInterceptor#before()
     * @see BatchInterceptor#before()
     * @see ChainInterceptor#before()
     * @param GrailsParameterMap Map of params created from the request data
     * @param LinkedHashMap map of variables defining endpoint request variables
     * @return returns false if request variable keys do not match expected endpoint keys
     */
    boolean checkURIDefinitions(GrailsParameterMap params,LinkedHashMap requestDefinitions){
        ArrayList reservedNames = ['batchLength','batchInc','chainInc','apiChain','apiResult','combine','_','batch','max','offset','apiObjectVersion']
        try {
            String authority = getUserRole() as String
            ArrayList temp = []
            if (requestDefinitions["${authority}"]) {
                temp = requestDefinitions["${authority}"] as ArrayList
            } else if (requestDefinitions['permitAll'][0] != null) {
                temp = requestDefinitions['permitAll'] as ArrayList
            }

            ArrayList requestList = (temp != null) ? temp.collect() { it.name } : []

            if (requestList.contains('*')) {
                return true
            } else {
                LinkedHashMap methodParams = getMethodParams(params)
                ArrayList paramsList = methodParams.keySet() as ArrayList
                // remove reservedNames from List

                reservedNames.each() { paramsList.remove(it) }

                //println("paramslist:" + paramsList)
                //println("requestlist:" + requestList)

                if (paramsList.size() == requestList.intersect(paramsList).size()) {
                    return true
                }
            }
            return false
        }catch(Exception e) {
           throw new Exception("[ApiCommProcess :: checkURIDefinitions] : Exception - full stack trace follows:",e)
        }
        return false
    }

    /**
     * Given a request method, format, params and response result, Formats response; returns a parsed string based on format
     * @see ApiFrameworkInterceptor#after()
     * @see BatchInterceptor#after()
     * @see ChainInterceptor#after()
     * @param RequestMethod mthd
     * @param String format
     * @param GrailsParameterMap Map of params created from the request data
     * @param HashMap result
     * @return
     */
    String parseResponseMethod(RequestMethod mthd, String format, GrailsParameterMap params, LinkedHashMap result){
        String content
        switch(mthd.getKey()) {
            case 'PURGE':
                // cleans cache; disabled for now
                break;
            case 'TRACE':
                break;
            case 'HEAD':
                break;
            case 'OPTIONS':
                String doc = getApiDoc(params)
                content = doc
                break;
            case 'GET':
            case 'PUT':
            case 'POST':
            case 'DELETE':
                switch(format){
                    case 'XML':
                        content = result as XML
                        break
                    case 'JSON':
                    default:
                        content = result as JSON
                }
                break;
        }

        return content
    }

    LinkedHashMap parseBatchResponseMethod(RequestMethod mthd, String format, LinkedHashMap result){
        LinkedHashMap content
        switch(mthd.getKey()) {
            case 'GET':
                // placeholder
            case 'PUT':
                // placeholder
            case 'POST':
                // placeholder
            case 'DELETE':
                content = result
                break
        }

        return content
    }

    /**
     * Given the request method and request params, format response for preHandler based upon request method
     * @see ApiFrameworkInterceptor#before()
     * @see BatchInterceptor#before()
     * @see ChainInterceptor#before()
     * @param RequestMethod mthd
     * @param GrailsParameterMap Map of params created from the request data
     * @return
     */
    String parseRequestMethod(RequestMethod mthd, GrailsParameterMap params){
        String content
        switch(mthd.getKey()) {
            case 'PURGE':
                // cleans cache; disabled for now
                break;
            case 'TRACE':
                // placeholder
                break;
            case 'HEAD':
                // placeholder
                break;
            case 'OPTIONS':
                content = getApiDoc(params)
                break;
        }

        return content
    }

    /**
     * Given the returning resource and a list of response variables, creates and returns a HashMap from request params sent that match endpoint params
     * @see ApiCommLayer#handleApiResponse(LinkedHashMap, List, RequestMethod, String, HttpServletResponse, HashMap, GrailsParameterMap)
     * @see ApiCommLayer#handleBatchResponse(LinkedHashMap, List, RequestMethod, String, HttpServletResponse, HashMap, GrailsParameterMap)
     * @see ApiCommLayer#handleChainResponse(LinkedHashMap, List, RequestMethod, String, HttpServletResponse, HashMap, GrailsParameterMap)
     * @param HashMap model
     * @param ArrayList responseList
     * @return
     */
    LinkedHashMap parseURIDefinitions(LinkedHashMap model,ArrayList responseList){
        if(model[0].getClass().getName()=='java.util.LinkedHashMap') {
            model.each() { key, val ->
                model[key] = parseURIDefinitions(val, responseList)
            }
            return model
        }else{
            try {
                ArrayList paramsList = (model.size()==0)?[:]:model.keySet() as ArrayList
                paramsList?.removeAll(optionalParams)
                if (!responseList.containsAll(paramsList)) {
                    paramsList.removeAll(responseList)
                    paramsList.each() { it2 ->
                        model.remove(it2.toString())
                    }

                    if (!paramsList) {
                        return [:]
                    } else {
                        return model
                    }
                } else {
                    return model
                }

            } catch (Exception e) {
                throw new Exception('[ApiCommProcess :: parseURIDefinitions] : Exception - full stack trace follows:', e)
            }
        }
    }


    // used in ApiCommLayer
    /**
     * Given request method and endpoint method/protocol,tests for endpoint method matching request method. Will also return true if
     * request method is Rest Alternative; returns boolean
     * @see ApiCommLayer#handleApiRequest(List, String, RequestMethod, HttpServletResponse, GrailsParameterMap)
     * @see ApiCommLayer#handleBatchRequest(List, String, RequestMethod, HttpServletResponse, GrailsParameterMap)
     * @see ApiCommLayer#handleChainRequest(List, String, RequestMethod, HttpServletResponse, GrailsParameterMap)
     * @param String protocol
     * @param RequestMethod mthd
     * @return
     */
    boolean isRequestMatch(String protocol,RequestMethod mthd){
        if(RequestMethod.isRestAlt(mthd.getKey())){
            return true
        }else{
            if(protocol == mthd.getKey()){
                return true
            }else{
                return false
            }
        }
        return false
    }

    /*
    * TODO : USED FOR TEST
    List getRedirectParams(GrailsParameterMap params){
        def uri = grailsApplication.mainContext.servletContext.getControllerActionUri(request)
        return uri[1..(uri.size()-1)].split('/')
    }
    */


    /**
     * Given the request params, returns a parsed HashMap of all request params NOT found in optionalParams List
     * @see checkURIDefinitions(GrailsParameterMap,HashMap)
     * @param GrailsParameterMap Map of params created from the request data
     * @return
     */
    LinkedHashMap getMethodParams(GrailsParameterMap params){
        try{
            LinkedHashMap paramsRequest = [:]
            paramsRequest = params.findAll { it2 -> !optionalParams.contains(it2.key) }
            return paramsRequest
        }catch(Exception e){
            throw new Exception('[ApiCommProcess :: getMethodParams] : Exception - full stack trace follows:',e)
        }
        return [:]
    }

    /**
     * Given an ArrayList of authorities associated with endpoint, determines if User authorities match; returns boolean
     * @see #getApiDoc(GrailsParameterMap)
     * @param ArrayList list
     * @return
     */
    Boolean hasRoles(ArrayList list) {
        if(springSecurityService.principal.authorities*.authority.any { list.contains(it) }){
            return true
        }
        return false
    }


    /**
     * Given a HashMap, parses and return a JSON String;
     * @deprecated
     * @see #getApiDoc
     * @param HashMap returns
     * @return
     */
    /*
    private String processJson(LinkedHashMap returns){
        // TODO: Need to compare multiple authorities
        try{
            LinkedHashMap json = [:]
            returns.each{ p ->
                p.value.each{ it ->
                    if(it) {
                        ParamsDescriptor paramDesc = it

                        LinkedHashMap j = [:]
                        if (paramDesc?.values) {
                            j["$paramDesc.name"] = []
                        } else {
                            String dataName = (['PKEY', 'FKEY', 'INDEX'].contains(paramDesc?.paramType?.toString())) ? 'ID' : paramDesc.paramType
                            j = (paramDesc?.mockData?.trim()) ? ["$paramDesc.name": "$paramDesc.mockData"] : ["$paramDesc.name": "$dataName"]
                        }
                        withPool(this.cores) { pool ->
                            j.eachParallel { key, val ->
                                if (val instanceof List) {
                                    LinkedHashMap child = [:]
                                    withExistingPool(pool, {
                                        val.eachParallel { it2 ->
                                            withExistingPool(pool, {
                                                it2.eachParallel { key2, val2 ->
                                                    child[key2] = val2
                                                }
                                            })
                                        }
                                    })
                                    json[key] = child
                                } else {
                                    json[key] = val
                                }
                            }
                        }
                    }
                }
            }

            String jsonReturn
            if(json){
                jsonReturn = json as JSON
            }
            return jsonReturn
        }catch(Exception e){
            throw new Exception("[ApiCommProcess :: processJson] : Exception - full stack trace follows:",e)
        }
    }
    */

    /**
     * Given a Map, will process cased on type of object and return a HashMap;
     * Called by the PostHandler
     * @see ApiFrameworkInterceptor#after()
     * @see BatchInterceptor#after()
     * @see ChainInterceptor#after()
     * @param Map map
     * @return
     */
    LinkedHashMap convertModel(Map map){
        try{
            LinkedHashMap newMap = [:]
            String k = map.entrySet().toList().first().key

            if(map && (!map?.response && !map?.metaClass && !map?.params)){
                if (DomainClassArtefactHandler?.isDomainClass(map[k].getClass()) && map[k]!=null) {
                    newMap = formatDomainObject(map[k])
                    return newMap
                } else if(['class java.util.LinkedList', 'class java.util.ArrayList'].contains(map[k].getClass().toString())) {
                    newMap = formatList(map[k])
                    return newMap
                } else if(['class java.util.Map', 'class java.util.LinkedHashMap','class java.util.HashMap'].contains(map[k].getClass().toString())) {
                    newMap = formatMap(map[k])
                    return newMap
                }
            }
            return newMap
        }catch(Exception e){
            throw new Exception("[ApiCommProcess :: convertModel] : Exception - full stack trace follows:",e)
        }
    }


    /**
     * Given an Object detected as a DomainObject, processes in a standardized format and returns a HashMap;
     * Used by convertModel and called by the PostHandler
     * @see #convertModel(Map)
     * @param Object data
     * @return
     */
    LinkedHashMap formatDomainObject(Object data){
        try {
            LinkedHashMap newMap = [:]

            newMap.put('id', data?.id)
            newMap.put('version', data?.version)

            //DefaultGrailsDomainClass d = new DefaultGrailsDomainClass(data.class)

            DefaultGrailsDomainClass d = grailsApplication?.getArtefact(DomainClassArtefactHandler.TYPE, data.class.getName())

            if (d!=null) {
                // println("PP:"+d.persistentProperties)
                d?.persistentProperties?.each(){ it ->
                    if (it?.name) {
                        if (DomainClassArtefactHandler.isDomainClass(data[it.name].getClass())) {
                            newMap["${it.name}Id"] = data[it.name].id
                        } else {
                            newMap[it.name] = data[it.name]
                        }
                    }
                }
            }
            return newMap
        }catch(Exception e){
           throw new Exception("[ApiCommProcess :: formatDomainObject] : Exception - full stack trace follows:",e)
        }
    }


    /**
     * Given a LinkedHashMap detected as a Map, processes in a standardized format and returns a LinkedHashMap;
     * Used by convertModel and called by the PostHandler
     * @see #convertModel(Map)
     * @param LinkedHashMap map
     * @return
     */
    LinkedHashMap formatMap(HashMap map){
        LinkedHashMap newMap = [:]
        if(map) {
            map.each() { key, val ->
                if (val) {

                    if (java.lang.Class.isInstance(val.class)) {
                        newMap[key] = ((val in java.util.ArrayList || val in java.util.List) || val in java.util.Map) ? val : val.toString()
                    } else if (DomainClassArtefactHandler?.isDomainClass(val.getClass())) {
                        newMap[key] = formatDomainObject(val)
                    } else {
                        newMap[key] = ((val in java.util.ArrayList || val in java.util.List) || (val in java.util.Map || val in java.util.Map || val in java.util.LinkedHashMap)) ? val : val.toString()
                    }
                }
            }
        }
        return newMap
    }


    /**
     * Given a List detected as a List, processes in a standardized format and returns a HashMap;
     * Used by convertModel and called by the PostHandler
     * @see #convertModel(Map)
     * @param ArrayList list
     * @return
     */
    LinkedHashMap formatList(List list){
        LinkedHashMap newMap = [:]
        if(list) {
            list.eachWithIndex() { val, key ->
                if (val) {
                    if (val instanceof java.util.ArrayList || val instanceof java.util.List) {
                        newMap[key] = ((val in java.util.ArrayList || val in java.util.List) || val in java.util.Map) ? val : val.toString()
                    } else {
                        if (DomainClassArtefactHandler?.isDomainClass(val.getClass())) {
                            newMap[key] = formatDomainObject(val)
                        } else {
                            newMap[key] = ((val in java.util.ArrayList || val in java.util.List) || val in java.util.Map) ? list[key] : val.toString()
                        }
                    }
                }
            }
        }
        return newMap
    }

    // TODO : add to ChainInterceptor
    /**
     * Given api version and a controllerName/className, tests whether cache exists; returns boolean
     * @see ApiFrameworkInterceptor#before()
     * @see BatchInterceptor#before()
     * @param Integer version
     * @param String className
     * @return
     */
    boolean isCachedResult(Integer version, String className){

        Class clazz = grailsApplication.domainClasses.find { it.clazz.simpleName == className }.clazz

        HibernateCriteriaBuilder c = clazz.createCriteria()

        long currentVersion = c.get {
            projections {
                property('version')
            }
            maxResults(1)
            order('version', 'desc')
        }

        return (currentVersion > version)?false:true
    }

    // interceptor::after (response)
    /**
     * Given request, test whether current request sent is an api chain; returns boolean
     * @see ChainInterceptor#before()
     * @param String contentType
     * @return
     */
    boolean isChain(String contentType){
        try{
            switch(contentType){
                case 'text/xml':
                case 'application/xml':
                    if(request.XML?.chain){
                        return true
                    }
                    break
                case 'text/json':
                case 'application/json':
                default:
                    if(request.JSON?.chain){
                        return true
                    }
                    break
            }
            return false
        }catch(Exception e){
            throw new Exception("[ApiResponseService :: isChain] : Exception - full stack trace follows:",e)
        }
    }

    // interceptor::before
    /**
     * Returns config variable representing number of seconds for throttle
     * @see ApiFrameworkInterceptor#before()
     * @see BatchInterceptor#before()
     * @see ChainInterceptor#before()
     * @return
     */
    String getThrottleExpiration(){
        return Holders.grailsApplication.config.apitoolkit.throttle.expires as String
    }

    // interceptor::before
    /**
     * Given the contentLength of the response, tests to see if rateLimit or dataLimit have been reached or supassed; returns boolean
     * @see ApiFrameworkInterceptor#before()
     * @see ApiFrameworkInterceptor#after()
     * @param int contentLength
     * @return
     */
    boolean checkLimit(int contentLength){
        HashMap throttle = Holders.grailsApplication.config.apitoolkit.throttle as HashMap
        HashMap rateLimit = throttle.rateLimit as HashMap
        HashMap dataLimit = throttle.dataLimit as HashMap
        ArrayList roles = rateLimit.keySet() as ArrayList
        String auth = getUserRole()

        if(roles.contains(auth)){
            //String userId = getUserId()
            String userId = springSecurityService.loggedIn?springSecurityService.principal.id : null
            def lcache = throttleCacheService.getThrottleCache(userId)

            if(lcache['timestamp']==null) {
                int currentTime= System.currentTimeMillis() / 1000
                int expires = currentTime+((Integer)Holders.grailsApplication.config.apitoolkit.throttle.expires)
                LinkedHashMap cache = ['timestamp': currentTime, 'currentRate': 1, 'currentData':contentLength,'locked': false, 'expires': expires]
                response.setHeader("Content-Length", "${contentLength}")
                throttleCacheService.setThrottleCache(userId, cache)
                return true
            }else{
                if(lcache['locked']==false) {

                    int userLimit = rateLimit["${auth}"] as Integer
                    int userDataLimit = dataLimit["${auth}"] as Integer
                    if(lcache['currentRate']>=userLimit || lcache['currentData']>=userDataLimit){
                        // TODO : check locked (and lock if not locked) and expires
                        int now = System.currentTimeMillis() / 1000
                        if(lcache['expires']<=now){
                            currentTime= System.currentTimeMillis() / 1000
                            expires = currentTime+((Integer)Holders.grailsApplication.config.apitoolkit.throttle.expires)
                            cache = ['timestamp': currentTime, 'currentRate': 1, 'currentData':contentLength,'locked': false, 'expires': expires]
                            response.setHeader('Content-Length', "${contentLength}")
                            throttleCacheService.setThrottleCache(userId, cache)
                            return true
                        }else{
                            lcache['locked'] = true
                            throttleCacheService.setThrottleCache(userId, lcache)
                            return false
                        }
                        return false
                    }else{
                        lcache['currentRate']++
                        lcache['currentData']+=contentLength
                        response.setHeader('Content-Length', "${contentLength}")
                        throttleCacheService.setThrottleCache(userId, lcache)
                        return true
                    }
                    return false
                }else{
                    return false
                }
            }
        }

        return true
    }

    // interceptor::before
    /**
     * Returns concatenated IDS as a HASH used as ID for the API cache
     * @see ApiFrameworkInterceptor#before()
     * @see BatchInterceptor#before()
     * @see ChainInterceptor#before()
     * @param GrailsParameterMap Map of params created from the request data
     * @param LinkedHashMap List of ids required when making request to endpoint
     * @return a hash from all id's needed when making request to endpoint
     */
    String createCacheHash(GrailsParameterMap params, LinkedHashMap receives){
        String hashString = ''
        String authority = getUserRole() as String
        ArrayList temp = []
        if (receives["${authority}"]) {
            temp = receives["${authority}"] as ArrayList
        } else if (receives['permitAll'][0] != null) {
            temp = receives['permitAll'] as ArrayList
        }

        ArrayList receivesList = (temp != null)?temp.collect(){ it.name }:[]
        receivesList.each(){ it ->
            hashString += params[it] + "/"
        }
        return hashWithGuava(hashString)
    }

    protected static String hashWithGuava(final String originalString) {
        final String sha256hex = Hashing.sha256().hashString(originalString, StandardCharsets.UTF_8).toString()
        return sha256hex;
    }
}
