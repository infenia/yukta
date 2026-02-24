/*
 * Copyright 2026 Infenia Private Limited
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
package com.infenia.jagratha.mapper;

import com.infenia.jagratha.model.AppConfigData;
import com.infenia.jagratha.model.ConfigRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Mapper for converting between ConfigRequest DTO and AppConfigData service record. */
@Mapper(componentModel = "spring")
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface AppConfigMapper {

  /**
   * Map ConfigRequest and sessionId to AppConfigData.
   *
   * @param request the config request
   * @param sessionId the session identifier
   * @return the app config data
   */
  @Mapping(target = "sessionId", source = "sessionId")
  AppConfigData toData(ConfigRequest request, String sessionId);
}
