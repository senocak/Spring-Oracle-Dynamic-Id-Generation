package com.github.senocak

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer
import org.springframework.core.io.ClassPathResource
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
@ComponentScan(basePackages = ["com.github.senocak"])
@EnableWebMvc
@Import(OracleConfiguration::class)
class AppConfig : WebMvcConfigurer {

    companion object {
        @Bean
        @JvmStatic
        fun propertySourcesPlaceholderConfigurer(): PropertySourcesPlaceholderConfigurer {
            val configurer = PropertySourcesPlaceholderConfigurer()
            val yaml = YamlPropertiesFactoryBean()
            yaml.setResources(ClassPathResource("application.yml"))
            configurer.setProperties(yaml.getObject()!!)
            return configurer
        }
    }
}
