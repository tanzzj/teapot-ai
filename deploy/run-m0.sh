#!/bin/bash
# M0 兼容性验证运行器：密码取自 app.env，不经命令行明文传递
source /main/apps/teapot-ai/app.env
/opt/rising-sun/jdk21/bin/java -jar /tmp/m0-app.jar "$TEAPOT_AI_DB_PASSWORD"
