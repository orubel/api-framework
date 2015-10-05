package net.nosegrind.apiframework

import grails.config.Config
import grails.core.support.GrailsConfigurationAware
import net.nosegrind.apiframework.comm.ApiRequestService
import net.nosegrind.apiframework.comm.ApiResponseService

import javax.servlet.http.HttpServletResponse




/* ****************************************************************************
 * Copyright 2014 Owen Rubel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *****************************************************************************/


class DomainInterceptor implements GrailsConfigurationAware{

    int order = HIGHEST_PRECEDENCE + 999


	ApiRequestService apiRequestService
	ApiResponseService apiResponseService
	ApiDomainService apiDomainService
	ApiCacheService apiCacheService


	String apiName
    String entryPoint

	void setConfiguration(Config cfg) {
		String apiVersion = cfg.info.app.version
		this.entryPoint = "d${apiVersion}"
		match(uri:"/${this.entryPoint}/**")
	}

	boolean before(){
		println("##### DOMAININTERCEPTOR (BEFORE)")

		params.format = request.format.toUpperCase()


		Map methods = ['get':'show','put':'update','post':'create','delete':'delete']
		try{
			//if(request.class.toString().contains('SecurityContextHolderAwareRequestWrapper')){

				LinkedHashMap cache = (params.controller)?apiCacheService.getApiCache(params.controller):[:]

				if(cache){
					params.apiObject = (params.apiObjectVersion)?params.apiObjectVersion:cache['currentStable']['value']
					if(!params.action){ 
						String methodAction = methods[request.method.toLowerCase()]
						if(!cache[params.apiObject][methodAction]){
							params.action = cache[params.apiObject]['defaultAction']
						}else{
							params.action = methods[request.method.toLowerCase()]
							
							// FORWARD FOR REST DEFAULTS WITH NO ACTION
							List tempUri = request.getRequestURI().split("/")
							if(tempUri[2].contains('dispatch') && "${params.controller}.dispatch" == tempUri[2] && !cache[params.apiObject]['domainPackage']){
								forward(controller:params.controller,action:params.action,params:params)
								return false
							}
						}
					}
							
					// SET PARAMS AND TEST ENDPOINT ACCESS (PER APIOBJECT)
					boolean result = apiRequestService.handleApiRequest(cache,request,params,this.entryPoint)

					//HANDLE DOMAIN RESOLUTION
					if(cache[params.apiObject]['domainPackage']){
						// SET PARAMS AND TEST ENDPOINT ACCESS (PER APIOBJECT)
						if(result){
							def model
							switch(methods[request.method.toLowerCase()]){
								case 'show':
									model = apiDomainService.showInstance(cache,params)
									break
								case 'update':
									model = apiDomainService.updateInstance(cache,params)
									break
								case 'create':
									model = apiDomainService.createInstance(cache,params)
									break
								case 'delete':
									model = apiDomainService.deleteInstance(cache,params)
                                    if(!model) {
                                        model = [:]
                                    }
									break
							}

							if(!model && request.method.toLowerCase()!='delete'){
								render(status:HttpServletResponse.SC_BAD_REQUEST)
								return false
							}

							def newModel = apiResponseService.formatDomainObject(model)
							LinkedHashMap content = apiResponseService.handleApiResponse(cache,request,response,newModel,params)

							if(request.method.toLowerCase()=='delete' && content.apiToolkitContent==null){
								render(status:HttpServletResponse.SC_OK)
								return false
							}else{
								render(text:content.apiToolkitContent, contentType:"${content.apiToolkitType}", encoding:content.apiToolkitEncoding)
								return false
							}
						}
						//return result
					}else{
						return result
					}
				}
			//}
			
			return false

		}catch(Exception e){
			log.error("[ApiToolkitFilters :: preHandler] : Exception - full stack trace follows:", e);
			return false
		}
	}
			

	boolean after(){
		println("##### DOMAININTERCEPTOR (AFTER)")
		try{
			if(!model){
				render(status:HttpServletResponse.SC_BAD_REQUEST)
				return false
			}

			Map newModel = (model)?apiResponseService.convertModel(model):model
			LinkedHashMap cache = (params.controller)?apiCacheService.getApiCache(params.controller):[:]

			LinkedHashMap content = apiResponseService.handleApiResponse(cache,request,response,newModel,params)
				
			if(content){
                render(text:content.apiToolkitContent, contentType:"${content.apiToolkitType}", encoding:content.apiToolkitEncoding)
                return false
			}

			return false
	   }catch(Exception e){
		   log.error("[ApiToolkitFilters :: apitoolkit.after] : Exception - full stack trace follows:", e);
		   return false
	   }

	}
}

