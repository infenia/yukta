package com.infenia.jagratha.mapper;

import com.infenia.jagratha.model.AppConfigData;
import com.infenia.jagratha.model.ConfigRequest;
import org.mapstruct.Mapper;

/** Mapper for converting between ConfigRequest DTO and AppConfigData service record. */
@Mapper(componentModel = "spring")
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface AppConfigMapper {

  /**
   * Map ConfigRequest to AppConfigData.
   *
   * @param request the config request
   * @return the app config data
   */
  AppConfigData toData(ConfigRequest request);
}
