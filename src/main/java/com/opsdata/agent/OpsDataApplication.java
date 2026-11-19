package com.opsdata.agent;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

@SpringBootApplication
public class OpsDataApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpsDataApplication.class);

    public static void main(String[] args) throws Exception {
        // --seed 是独立的建库命令。生成数据库后直接退出，方便单独重建样例数据。
        if (args.length == 1 && "--seed".equals(args[0])) {
            Path database = Path.of("data", "ops.db");
            SeedDatabase.create(Path.of("seed.sql"), database);
            System.out.println("SQLite 数据库已生成：" + database.toAbsolutePath());
            return;
        }

        // 服务启动成功后，使用服务器真正监听的端口打印浏览器地址。
        var context = SpringApplication.run(OpsDataApplication.class, args);
        if (context instanceof ServletWebServerApplicationContext webContext) {
            int port = webContext.getWebServer().getPort();
            if (port > 0) {
                String path = webContext.getServletContext().getContextPath();
                LOGGER.info("Open in browser: http://localhost:{}{}/", port, path);
            }
        }
    }
}
