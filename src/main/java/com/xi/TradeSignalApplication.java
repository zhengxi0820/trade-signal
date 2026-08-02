package com.xi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.xi.orm.mapper")
@SpringBootApplication
public class TradeSignalApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeSignalApplication.class, args);
    }

}
