package com.dripswap.bff.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.dripswap.bff.repository")
public class MyBatisConfig {}

