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
   * Map ConfigRequest to AppConfigData.
   *
   * @param request the config request
   * @return the app config data
   */
  @Mapping(source = "modifiedFile", target = "fileLogDir")
  @Mapping(source = "results", target = "resultLogDir")
  AppConfigData toData(ConfigRequest request);
}
