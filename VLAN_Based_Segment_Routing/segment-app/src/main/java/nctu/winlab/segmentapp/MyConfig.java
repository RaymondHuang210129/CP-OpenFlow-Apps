/*
 * Copyright 2019-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.segmentapp;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;
import org.onosproject.net.config.basics.BasicElementConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;


/**
 * My Config class.
 */
public class MyConfig extends Config<ApplicationId>{

    // The JSON file should contain one field "name".
    public static final String CONFIG = "config";
    // For ONOS to check whether an uploaded configuration is valid.
    @Override
    public boolean isValid(){
        return true;//hasOnlyFields(MY_NAME);
    }

    // To retreat the value.
    public String getConfig(){
        String config = get(CONFIG, null);
        return config;
    }   
}