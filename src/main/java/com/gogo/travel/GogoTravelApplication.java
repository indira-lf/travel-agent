package com.gogo.travel;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author Hollis
 */
@SpringBootApplication
@MapperScan("com.gogo.travel.**.mapper")
public class GogoTravelApplication {

    public static void main(String[] args) {
        SpringApplication.run(GogoTravelApplication.class, args);
    }
}
