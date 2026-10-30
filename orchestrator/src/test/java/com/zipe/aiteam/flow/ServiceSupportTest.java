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
  Files.writeString(p,"{\"healthy\":true,\"endpoints\":[{\"audience\":\"Windows host\",\"url\":\"http://127.0.0.1:8088/view/chat\",\"probe_url\":\"http://192.0.2.20/view/chat\",\"http_status\":200}]}" );
 }
 @Test void persistedDiagnosticsOnlyRecoverMatchingIdentityAndLiveDeadline() throws Exception {
  var dir=home.resolve("service-context");Files.createDirectories(dir);
  Files.writeString(dir.resolve("sit.json"),"{\"target\":\"sit\",\"access_endpoints\":[{}]}");
  var job=home.resolve("service-support/1049/job.json");Files.createDirectories(job.getParent());
  var data=new ObjectMapper().createObjectNode().put("version",1).put("task","1049").put("parent","1048")
          .put("target","sit").put("state","RUNNING").put("deadline",java.time.Instant.now().getEpochSecond()+60);
  Files.writeString(job,data.toString());
  assertThat(ServiceSupport.recoverable(home,"1049","1048","sit")).isTrue();
  assertThat(ServiceSupport.recoverable(home,"1049","1111","sit")).isFalse();
  Files.writeString(job,data.put("deadline",1).toString());
  assertThat(ServiceSupport.recoverable(home,"1049","1048","sit")).isFalse();
 }
 @Test void oldHealthyEvidenceIsNotCurrentWhileNewDiagnosticIsRunning() throws Exception {
  evidence();Path file=home.resolve("service-support/1049/job.json");
  Files.writeString(file,"{\"state\":\"RUNNING\",\"generation\":\"new\"}");
  assertThat(ServiceSupport.current(home,"1049").path("healthy").asBoolean()).isFalse();
  assertThat(ServiceSupport.current(home,"1049").has("endpoints")).isFalse();
  Files.writeString(file,"{\"state\":\"COMPLETED\",\"generation\":\"new\"}");
  assertThat(ServiceSupport.current(home,"1049").has("endpoints")).isFalse();
 }
 @Test void wrongUrlAnswerIsAutomaticallyRejected() throws Exception {
  evidence();assertThat(ServiceSupport.replyReason(home,"1049","網址無法打開","請開http://192.0.2.20/" )).contains("完整入口");
  assertThat(ServiceSupport.replyReason(home,"1049","網址無法打開","請在Windows開http://127.0.0.1:8088/view/chat")).isNull();
 }
 @Test void completedTaskExecutionAdviceIsAutomaticallyCorrected() throws Exception {
  evidence();assertThat(ServiceSupport.replyReason(home,"1049","網址無法打開","請開http://127.0.0.1:8088/view/chat，若無法開啟輸入`@Infrastructure Agent 執行 檢查轉送`" )).contains("已結案");
 }
 @Test void trustedFallbackKeepsAudienceAndProbeLimit() throws Exception {
  evidence();assertThat(ServiceSupport.verifiedReply(home,"1049")).contains("Windows host","http://127.0.0.1:8088/view/chat","未宣稱已從你的電腦驗證");
 }
 @Test void deliveryMustProvideRegisteredFullUserEndpoint() throws Exception {
  evidence();var json=new ObjectMapper();
  assertThat(ServiceSupport.deliveryReason(home,"1049",json.readTree("{\"summary\":\"http://192.0.2.20/\"}"))).contains("使用者入口");
  assertThat(ServiceSupport.deliveryReason(home,"1049",json.readTree("{\"summary\":\"http://127.0.0.1:8088/view/chat\"}"))).isNull();
 }
 @Test void missingEvidenceDoesNotPassRegisteredDelivery() {
  assertThat(ServiceSupport.deliveryReason(home,"1049",new ObjectMapper().createObjectNode())).contains("缺少本次診斷");
 }
 @Test void sandboxUserHostOnlyAppliesWithoutRegisteredEndpoints() throws Exception {
  // #1003：接沙箱時登錄的位址讓系統自己實測交付網址；管理者登錄入口時仍以登錄為準。
  Path dir=Files.createDirectories(home.resolve("service-context"));
  Files.writeString(dir.resolve("sandbox.json"),"{\"target\":\"sandbox\",\"user_host\":\"192.0.2.20\"}");
  assertThat(ServiceSupport.userHost(home,"sandbox")).isEqualTo("192.0.2.20");
  assertThat(ServiceSupport.configured(home,"sandbox")).isFalse();
  Files.writeString(dir.resolve("sandbox.json"),"{\"target\":\"sandbox\",\"user_host\":\"192.0.2.20\",\"access_endpoints\":[{\"audience\":\"a\"}]}");
  assertThat(ServiceSupport.userHost(home,"sandbox")).isNull();
  Files.writeString(dir.resolve("sandbox.json"),"{\"target\":\"other\",\"user_host\":\"192.0.2.20\"}");
  assertThat(ServiceSupport.userHost(home,"sandbox")).isNull();
  Files.writeString(dir.resolve("sandbox.json"),"{\"target\":\"sandbox\",\"user_host\":\"http://x/\"}");
  assertThat(ServiceSupport.userHost(home,"sandbox")).isNull();
 }
 @Test void derivedDeliveryProbesOnlyTheRegisteredHost() throws Exception {
  var json=new ObjectMapper();var probed=new java.util.ArrayList<String>();
  java.util.function.Function<String,Integer> ok=u->{probed.add(u);return 200;};
  var report=json.readTree("{\"service_status\":\"RUNNING\",\"summary\":\"內部 http://127.0.0.1:18080/app/ 正常；請開 http://192.0.2.20:18080/app/。另見 http://evil.example/x\"}");
  var pass=ServiceSupport.derivedDelivery("192.0.2.20",report,ok);
  assertThat(pass.block()).isNull();
  assertThat(probed).containsExactly("http://192.0.2.20:18080/app/");
  assertThat(pass.evidence()).contains("HTTP 200");
  assertThat(ServiceSupport.derivedDelivery("192.0.2.20",report,u->404).block()).contains("未通過","HTTP 404");
  assertThat(ServiceSupport.derivedDelivery("192.0.2.20",report,u->302).block()).contains("HTTP 302");
  assertThat(ServiceSupport.derivedDelivery("192.0.2.20",report,u->null).block()).contains("連不上");
  var noUrl=json.readTree("{\"service_status\":\"RUNNING\",\"summary\":\"服務在 VM 內 127.0.0.1 正常\"}");
  assertThat(ServiceSupport.derivedDelivery("192.0.2.20",noUrl,ok).block()).contains("缺少使用者入口","http://192.0.2.20:");
  var stopped=json.readTree("{\"service_status\":\"NOT_RUNNING\",\"summary\":\"\"}");
  assertThat(ServiceSupport.derivedDelivery("192.0.2.20",stopped,ok).block()).isNull();
 }
}
