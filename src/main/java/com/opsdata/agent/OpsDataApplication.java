package com.opsdata.agent;

import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OpsDataApplication {

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--seed".equals(args[0])) {
            Path database = Path.of("data", "ops.db");
            SeedDatabase.create(Path.of("seed.sql"), database);
            System.out.println("SQLite 数据库已生成：" + database.toAbsolutePath());
            return;
        }

        SpringApplication.run(OpsDataApplication.class, args);
    }
}
