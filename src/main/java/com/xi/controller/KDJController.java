package com.xi.controller;

import com.xi.convert.KDJConvert;
import com.xi.model.dto.KDJDTO;
import com.xi.model.param.KDJParam;
import com.xi.model.param.WorkDayParam;
import com.xi.model.vo.CrossStockVO;
import com.xi.model.vo.KDJVO;
import com.xi.model.vo.WorkDayVO;
import com.xi.service.KDJService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/kdj")
public class KDJController {

    @Autowired
    private KDJService kdjService;

    /**
     * 单票某周期KDJ序列（含交叉点标注），供KDJ线图展示
     */
    @GetMapping("/series")
    public List<KDJVO> getSeries(KDJParam kdjParam) {
        List<KDJDTO> kdjDTOList = kdjService.getAllKDJ(kdjParam);
        return KDJConvert.INSTANCE.toVO(kdjDTOList);
    }

    /**
     * 某周期截止周期出现金叉的股票列表，code为空=全市场
     */
    @GetMapping("/gold-cross")
    public List<CrossStockVO> getGoldCross(KDJParam kdjParam, HttpServletResponse response) {
        List<CrossStockVO> list = kdjService.getGold(kdjParam);
        markNotReady(kdjParam, response);
        return list;
    }

    /**
     * 某周期截止周期出现交易位的股票列表，code为空=全市场
     */
    @GetMapping("/trade-signal")
    public List<CrossStockVO> getTradeSignal(KDJParam kdjParam, HttpServletResponse response) {
        List<CrossStockVO> list = kdjService.getTradeSignalStockList(kdjParam);
        markNotReady(kdjParam, response);
        return list;
    }

    /**
     * 可选周期列表（已完结周期，时间倒序），供截止周期选择器
     */
    @GetMapping("/periods")
    public List<WorkDayVO> getPeriods(WorkDayParam param) {
        return kdjService.getPeriods(param);
    }

    /**
     * 全部股票的截止周期行情与 KDJ（不过滤），供「所有股票」列表，code为空=全市场
     */
    @GetMapping("/all-stocks")
    public List<CrossStockVO> getAllStocks(KDJParam kdjParam, HttpServletResponse response) {
        List<CrossStockVO> list = kdjService.getAllStocks(kdjParam);
        markNotReady(kdjParam, response);
        return list;
    }

    /** 周/月/季物化落后于请求截止周期时打未就绪头（前端提示自动补齐中） */
    private void markNotReady(KDJParam kdjParam, HttpServletResponse response) {
        if (!kdjService.isScanDataReady(kdjParam)) {
            response.setHeader("X-Data-Not-Ready", "1");
        }
    }

    /**
     * 清空全市场扫描结果缓存（运维兜底；日常失效靠数据水位自动完成）
     */
    @PostMapping("/cache/refresh")
    public Map<String, Integer> refreshCache() {
        return Map.of("cleared", kdjService.clearScanCache());
    }
}
