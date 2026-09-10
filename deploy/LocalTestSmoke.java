package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.runtime.*;
import com.zipe.aiteam.sandbox.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 隔離 clone 的無模型實測；不是 #1014 正式 QA 或人工核准。 */
public class LocalTestSmoke {
    public static void main(String[] args) throws Exception {
        var mapper=new ObjectMapper();
        FlowRun flow=mapper.readValue(Files.readString(Path.of(args[0])),FlowRun.class);
        var target=TeamCatalog.load(Path.of(args[1]),key->flow.snapshot.repository());
        var preview=TeamMigration.preview(flow.snapshot,target);
        Files.writeString(Path.of(args[3]).resolve("migration-preview.json"),mapper.writeValueAsString(preview));
        System.out.println("MIGRATION "+preview.token()+" changes="+preview.changes().size());
        Path cwd=Path.of(args[2]).toRealPath();
        if (!cwd.startsWith(Path.of(args[3]).toRealPath()) || cwd.equals(Path.of(flow.workspacePath)))
            throw new IllegalArgumentException("必須是隔離建置中的 clone");
        flow.snapshot=target;flow.node="verification";
        var props=new AiTeamProperties(){
            @Override public Path userHome(){return Path.of("/home/vboxuser");}
            @Override public Path codexBin(){return Path.of("/home/vboxuser/.local/node/bin/codex");}
        };
        var sandbox=new ExecutionSandbox(props);var runtime=new CodexRuntime(props,sandbox);
        var node=target.definition().nodes().get("verification");
        var policy=new ConfiguredExecutor(props,null,sandbox,null,null).policy(flow,node,runtime,cwd);
        var prepare=Class.forName("com.zipe.aiteam.runtime.CodexSandboxPreparation").getDeclaredMethod("prepare",Path.class);
        prepare.setAccessible(true);prepare.invoke(null,cwd);
        var req=new AgentRuntime.RuntimeRequest("isolated","verification",cwd,cwd.resolve("AGENTS.md"),"unused",List.of(),null,null,1,Duration.ofSeconds(30),policy,"gpt-5.6-sol");
        var check=CodexRuntime.class.getDeclaredMethod("checkSandbox",AgentRuntime.RuntimeRequest.class,Path.class);
        check.setAccessible(true);check.invoke(runtime,req,Path.of(args[3]).resolve("local-preflight.log"));
        var method=Class.forName("com.zipe.aiteam.runtime.CodexLocalTestPolicy").getDeclaredMethod("arguments",AgentRuntime.RuntimeRequest.class,List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked") List<String> flags=(List<String>)method.invoke(null,req,runtime.statePaths(props.userHome()));
        List<String> command=new ArrayList<>(List.of(props.codexBin().toString(),"sandbox"));command.addAll(flags);
        command.addAll(List.of("-C",cwd.toString(),"--","sh","-ec",
                "mvn -B -o -pl base-spring-boot-starter -Dtest=MailServiceFailoverTest,MailServiceLegacyCompatibilityTest test; "
                + "mkdir -p target/qa-docs; git archive HEAD doc-site llms.txt llms-full.txt | tar -x -C target/qa-docs; "
                + "npm --prefix target/qa-docs/doc-site ci --offline --ignore-scripts --no-audit --no-fund; "
                + "npm --prefix target/qa-docs/doc-site run build; "
                + "cmp llms.txt target/qa-docs/doc-site/build/llms.txt; cmp llms-full.txt target/qa-docs/doc-site/build/llms-full.txt; git status --porcelain"));
        Path output=Path.of(args[3]).resolve("real-tools.log");
        var wrapped=new ArrayList<>(sandbox.wrap("ai-team-localtools-"+UUID.randomUUID(),policy,cwd,command));
        wrapped.addAll(wrapped.indexOf("--"),List.of("-p","RuntimeMaxSec=480","-p","KillMode=control-group"));
        Process p=new ProcessBuilder(wrapped).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {p.getOutputStream().close();if(!p.waitFor(490,TimeUnit.SECONDS)||p.exitValue()!=0)throw new IllegalStateException("工具實測未通過，見 "+output);}
        finally {if(p.isAlive())p.destroyForcibly();}
        System.out.println("PASS isolated real SMTP tests and offline docs build; NOT task QA approval");
    }
}
