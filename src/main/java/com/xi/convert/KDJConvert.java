package com.xi.convert;

import com.xi.model.dto.KDJDTO;
import com.xi.model.vo.KDJVO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper
public interface KDJConvert {

    KDJConvert INSTANCE =  Mappers.getMapper(KDJConvert.class);

    List<KDJVO> toVO(List<KDJDTO> kdjdtoList);
}
