package com.zyh.archivemind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ArchiveMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchiveMindApplication.class, args);
    }

}
