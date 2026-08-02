package com.xi.orm.mapper;

import com.xi.model.query.WorkDayQuery;
import com.xi.orm.entity.WorkDayDO;

import java.util.List;

public interface WorkDayMapper {

    List<WorkDayDO> queryAll(WorkDayQuery workDayQuery);

}
