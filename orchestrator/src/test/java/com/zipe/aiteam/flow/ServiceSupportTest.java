package com.zipe.aiteam.flow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class ServiceSupportTest {
 @TempDir Path home;
 void evidence() throws Exception {
  Path p=home.resolve("service-support/1049/current.json");Files.createDirectories(p.getParent());
  Files.writeString(p,"{\"healthy\":true,\"endpoints\":[{\"audience\":\"Windows host\",\"url\":\"http://127.0.0.1:8088/view/chat\",\"probe_url\":\"http://172.30.46.20/view/chat\",\"http_status\":200}]}" );
 }
 @Test void wrongUrlAnswerIsAutomaticallyRejected() throws Exception {
  evidence();assertThat(ServiceSupport.replyReason(home,"1049","網址無法打開","請開http://172.30.46.20/" )).contains("完整入口");
  assertThat(ServiceSupport.replyReason(home,"1049","網址無法打開","請在Windows開http://127.0.0.1:8088/view/chat")).isNull();
 }
 @Test void trustedFallbackKeepsAudienceAndProbeLimit() throws Exception {
  evidence();assertThat(ServiceSupport.verifiedReply(home,"1049")).contains("Windows host","http://127.0.0.1:8088/view/chat","未宣稱已從你的電腦驗證");
 }
 @Test void deliveryMustProvideRegisteredFullUserEndpoint() throws Exception {
  evidence();var json=new ObjectMapper();
  assertThat(ServiceSupport.deliveryReason(home,"1049",json.readTree("{\"summary\":\"http://172.30.46.20/\"}"))).contains("使用者入口");
  assertThat(ServiceSupport.deliveryReason(home,"1049",json.readTree("{\"summary\":\"http://127.0.0.1:8088/view/chat\"}"))).isNull();
 }
 @Test void missingEvidenceDoesNotPassRegisteredDelivery() {
  assertThat(ServiceSupport.deliveryReason(home,"1049",new ObjectMapper().createObjectNode())).contains("缺少本次診斷");
 }
}
