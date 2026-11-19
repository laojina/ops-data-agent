package com.opsdata.agent;

import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OpsDataApplication {

    public static void main(String[] args) throws Exception {
        // --seed 是独立的建库命令。生成数据库后直接退出，方便单独重建样例数据。
        if (args.length == 1 && "--seed".equals(args[0])) {
            Path database = Path.of("data", "ops.db");
            SeedDatabase.create(Path.of("seed.sql"), database);
            System.out.println("SQLite 数据库已生成：" + database.toAbsolutePath());
            return;
        }

        // 正常模式启动 Spring Boot，由它监听 application.properties 配置的 8000 端口。
        SpringApplication.run(OpsDataApplication.class, args);
    }
}
