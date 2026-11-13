/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BuildQueueTest {
 @Inject BuildRunner runner;
 @Inject BuildLogs logs;
 @Inject BuildRetention retention;
 Long project;
 final BuildResource.RunRequest request=new BuildResource.RunRequest("main","CISYS_GIT_TEST");
 @BeforeEach void setup(){
  runner=io.quarkus.arc.ClientProxy.unwrap(runner);
  QuarkusTransaction.requiringNew().run(()->{Build.deleteAll();Project.deleteAll();});
  runner.concurrency=1;runner.queueLimit=100;
  project=QuarkusTransaction.requiringNew().call(()->{
   Project p=new Project();p.name="queue-test";p.ownerSubject="owner";p.repositoryUrl="https://github.com/example/test.git";p.persist();return p.id;
  });
 }
 @AfterEach void cleanup(){runner.concurrency=1;runner.queueLimit=100;SettingsResourceTest.clearQueue();QuarkusTransaction.requiringNew().run(()->{Build.deleteAll();Project.deleteAll();});}
 Build submit(){return runner.submit(project,"owner","https://github.com/example/original.git",request);}
 Build read(Long id){return QuarkusTransaction.requiringNew().call(()->Build.findById(id));}
 void finish(Long id){QuarkusTransaction.requiringNew().run(()->{Build b=Build.findById(id);b.status=Build.Status.PASSED;b.finishedAt=java.time.Instant.now();});}
 @Test void fifoAndSlots(){
  Build first=submit(),second=submit(),third=submit();
  assertEquals(Build.Status.QUEUED,first.status);assertNull(first.startedAt);
  assertEquals(first.id,runner.claim().id);assertNull(runner.claim());
  finish(first.id);assertEquals(second.id,runner.claim().id);
  finish(second.id);assertEquals(third.id,runner.claim().id);assertNull(runner.claim());
  Build stored=read(third.id);assertNotNull(stored.startedAt);
  assertEquals("https://github.com/example/original.git",stored.repositoryUrl);
  assertEquals(request.branch(),stored.branch);assertEquals(request.credentialRef(),stored.credentialRef);
  assertNull(stored.image);assertNull(stored.workflow);
 }
 @Test void concurrentClaimsNeverDuplicateOrExceedCapacity()throws Exception{
  runner.concurrency=3;for(int i=0;i<10;i++)submit();
  Set<Long> claimed=new HashSet<>();
  try(var pool=Executors.newFixedThreadPool(8)){
   List<Callable<Build>> calls=new ArrayList<>();for(int i=0;i<16;i++)calls.add(runner::claim);
   for(Future<Build> f:pool.invokeAll(calls)){Build b=f.get();if(b!=null)assertTrue(claimed.add(b.id));}
  }
  assertEquals(3,claimed.size());
  assertEquals(3L,QuarkusTransaction.requiringNew().call(()->Build.count("status",Build.Status.RUNNING)));
 }
 @Test void concurrentAdmissionsRespectQueueLimit()throws Exception{
  runner.queueLimit=3;int accepted=0,rejected=0;
  try(var pool=Executors.newFixedThreadPool(8)){
   List<Callable<Boolean>> calls=new ArrayList<>();for(int i=0;i<12;i++)calls.add(()->{try{submit();return true;}catch(WebApplicationException e){assertEquals(429,e.getResponse().getStatus());return false;}});
   for(Future<Boolean> f:pool.invokeAll(calls)){if(f.get())accepted++;else rejected++;}
  }
  assertEquals(3,accepted);assertEquals(9,rejected);
 }
 @Test void recoveryKeepsQueuedRequestsAndFailsInterruptedBuilds(){
  Build interrupted=submit(),waiting=submit();runner.claim();
  BuildRunner restarted=new BuildRunner();restarted.logs=logs;restarted.concurrency=1;restarted.queueLimit=100;
  try{
   restarted.recover(null);
   assertEquals(Build.Status.FAILED,read(interrupted.id).status);assertNotNull(read(interrupted.id).finishedAt);
   assertEquals(Build.Status.QUEUED,read(waiting.id).status);assertNull(read(waiting.id).startedAt);
   assertEquals(waiting.id,restarted.claim().id);assertNull(restarted.claim());
  }finally{restarted.stop();}
 }
 @Test void dispatcherRunsWaitingBuildAfterSlotBecomesFree()throws Exception{
  Build first=submit(),second=submit();
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
  List<Long> order=new CopyOnWriteArrayList<>();
  BuildRunner worker=new BuildRunner(){
   @Override void execute(Build b){
    order.add(b.id);
    try{if(b.id.equals(first.id)){entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}finish(b.id);}
    catch(InterruptedException e){Thread.currentThread().interrupt();}
    finally{done.countDown();}
   }
  };
  worker.logs=logs;worker.concurrency=1;worker.queueLimit=100;worker.recover(null);
  try{
   worker.dispatch();assertTrue(entered.await(10,TimeUnit.SECONDS));
   worker.dispatch();assertEquals(Build.Status.QUEUED,read(second.id).status);
   release.countDown();assertTrue(done.await(10,TimeUnit.SECONDS));
   worker.dispatch();
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
   while(read(second.id).status!=Build.Status.PASSED && System.nanoTime()<deadline)Thread.sleep(10);
   assertEquals(Build.Status.PASSED,read(second.id).status);
   assertEquals(List.of(first.id,second.id),order);
  }finally{release.countDown();worker.stop();}
 }
 @Test void stopWaitsForRunningBuildsToSaveTheirResult()throws Exception{
  Build running=submit();
  CountDownLatch entered=new CountDownLatch(1);
  BuildRunner worker=new BuildRunner(){
   @Override void execute(Build b){
    entered.countDown();
    try{Thread.sleep(60_000);}
    catch(InterruptedException e){
     // The interrupted build's cleanup is slow and needs the lock that saving a result takes.
     try{Thread.sleep(500);}catch(InterruptedException again){Thread.currentThread().interrupt();}
     complete(new Completion(b.id,Build.Status.FAILED,null,null,null,false,java.time.Instant.now()));
    }
   }
  };
  worker.logs=logs;worker.concurrency=1;worker.queueLimit=100;worker.recover(null);
  worker.dispatch();assertTrue(entered.await(10,TimeUnit.SECONDS));
  worker.stop();
  // stop() returns only after the build saved its result, while the database is still there.
  Build saved=read(running.id);
  assertEquals(Build.Status.FAILED,saved.status);assertNotNull(saved.finishedAt);
 }
 @Test void failedCompletionIsRetriedWithoutRerunningAndQueueResumes()throws Exception{
  Build first=submit(),second=submit();
  CountDownLatch attempted=new CountDownLatch(1),secondDone=new CountDownLatch(1);
  var failures=new java.util.concurrent.atomic.AtomicInteger(2);
  List<Long> executions=new CopyOnWriteArrayList<>();
  var finished=java.time.Instant.parse("2026-11-12T10:00:00Z");
  BuildRunner worker=new BuildRunner(){
   @Override void execute(Build b){
    executions.add(b.id);
    complete(new Completion(b.id,Build.Status.PASSED,"revision",0,"image-digest",true,finished));
    if(b.id.equals(first.id))attempted.countDown();else secondDone.countDown();
   }
   @Override void persistCompletion(Completion r){
    super.persistCompletion(r);
    // Fail after the SQL update so the test also verifies transaction rollback.
    if(r.id().equals(first.id) && failures.getAndDecrement()>0)throw new IllegalStateException("database unavailable");
   }
  };
  worker.logs=logs;worker.concurrency=1;worker.queueLimit=100;worker.recover(null);
  try{
   worker.dispatch();assertTrue(attempted.await(10,TimeUnit.SECONDS));
   assertEquals(Build.Status.RUNNING,read(first.id).status);
   assertNull(read(first.id).finishedAt);
   worker.dispatch(); // The second persistence attempt still fails.
   assertEquals(Build.Status.RUNNING,read(first.id).status);
   assertEquals(Build.Status.QUEUED,read(second.id).status);
   worker.dispatch(); // Database recovery frees the slot and starts the next build.
   assertTrue(secondDone.await(10,TimeUnit.SECONDS));
   Build saved=read(first.id);
   assertEquals(Build.Status.PASSED,saved.status);assertEquals(finished,saved.finishedAt);
   assertEquals("revision",saved.revision);assertEquals(0,saved.exitCode);
   assertEquals("image-digest",saved.imageDigest);assertTrue(saved.logTruncated);
   assertEquals(Build.Status.PASSED,read(second.id).status);
   worker.dispatch();assertEquals(List.of(first.id,second.id),executions);
  }finally{worker.stop();}
 }
 @Test void adminSettingsReplaceConfiguredLimits(){
  QuarkusTransaction.requiringNew().run(()->{Settings s=Settings.findById(1L);if(s==null){s=new Settings();s.persist();}s.maxConcurrentBuilds=2;s.maxQueuedBuilds=3;});
  Build first=submit(),second=submit();submit();
  WebApplicationException full=assertThrows(WebApplicationException.class,this::submit);assertEquals(429,full.getResponse().getStatus());
  assertEquals(first.id,runner.claim().id);assertEquals(second.id,runner.claim().id);assertNull(runner.claim());
 }
 @Test void recoveryRemovesOrphanedContainersAndCheckoutDirectories()throws Exception{
  Path root=Files.createTempDirectory("cisys-recovery-");
  Path stale=Files.createDirectory(root.resolve("cisys-old"));
  Files.writeString(stale.resolve("file"),"checkout");
  Path keep=Files.createDirectory(root.resolve("keep-me"));
  Build interrupted=submit();
  runner.claim();
  List<List<String>> calls=new CopyOnWriteArrayList<>();
  BuildRunner restarted=new BuildRunner(){
   @Override Path checkoutRoot(){return root;}
  };
  restarted.runtime="podman";
  restarted.commands=(args,timeout,limit)->{
   calls.add(List.copyOf(args));
   if(args.get(1).equals("ps"))return new BuildRunner.Result(0,false,"cisys-build-1\ncisys-build-2\nunrelated\n");
   if(args.get(1).equals("images"))return new BuildRunner.Result(0,false,"localhost/cisys-build-3\ndocker.io/library/alpine\nlocalhost/cisys-build-x\n");
   return new BuildRunner.Result(0,false,"");
  };
  restarted.logs=logs;restarted.concurrency=1;restarted.queueLimit=100;
  try{
   restarted.recover(null);
   assertEquals(List.of(
       List.of("podman","ps","-a","--format","{{.Names}}"),
       List.of("podman","rm","-f","cisys-build-1"),
       List.of("podman","rm","-f","cisys-build-2"),
       List.of("podman","images","--format","{{.Repository}}"),
       List.of("podman","rmi","-f","localhost/cisys-build-3")),calls);
   assertFalse(Files.exists(stale),"the stale checkout is removed");
   assertTrue(Files.exists(keep),"unrelated directories are kept");
   assertEquals(Build.Status.FAILED,read(interrupted.id).status);
  }finally{
   restarted.stop();
   BuildRunner.delete(root);
  }
 }
 @Test void cleanupFailureDoesNotStopRecovery(){
  Build interrupted=submit();
  runner.claim();
  BuildRunner restarted=new BuildRunner(){
   @Override Path checkoutRoot(){return Path.of("/definitely/not/a/checkout/root");}
  };
  restarted.runtime="podman";
  restarted.commands=(args,timeout,limit)->{
   if(args.get(1).equals("ps"))return new BuildRunner.Result(0,false,"cisys-build-1\n");
   return new BuildRunner.Result(1,false,"container runtime unavailable");
  };
  restarted.logs=logs;restarted.concurrency=1;restarted.queueLimit=100;
  try{
   assertDoesNotThrow(()->restarted.recover(null));
   assertEquals(Build.Status.FAILED,read(interrupted.id).status);
  }finally{restarted.stop();}
 }
 @Test void rejectedScheduleReturnsTheBuildToTheQueue()throws Exception{
  Build queued=submit();
  AtomicBoolean reject=new AtomicBoolean(true);
  List<Long> executed=new CopyOnWriteArrayList<>();
  BuildRunner worker=new BuildRunner(){
   @Override void schedule(Runnable task){
    if(reject.get())throw new RejectedExecutionException("worker stopped");
    super.schedule(task);
   }
   @Override void execute(Build b){executed.add(b.id);finish(b.id);}
  };
  worker.logs=logs;worker.concurrency=1;worker.queueLimit=100;worker.recover(null);
  try{
   worker.dispatch();
   Build after=read(queued.id);
   assertEquals(Build.Status.QUEUED,after.status);
   assertNull(after.startedAt);
   reject.set(false);
   worker.dispatch();
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
   while(executed.isEmpty() && System.nanoTime()<deadline)Thread.sleep(10);
   assertEquals(List.of(queued.id),executed);
  }finally{worker.stop();}
 }
 @Test void retentionProtectsQueuedBuilds(){
  Build waiting=submit();
  QuarkusTransaction.requiringNew().run(()->{Build b=Build.findById(waiting.id);b.startedAt=java.time.Instant.EPOCH;});
  retention.purge(java.time.Instant.now(),1,1);
  assertEquals(Build.Status.QUEUED,read(waiting.id).status);
 }
 @Test void cancelledQueuedBuildIsNeverClaimed(){
  Build cancelled=submit(),next=submit();
  assertTrue(runner.cancel(cancelled.id));
  Build stored=read(cancelled.id);assertEquals(Build.Status.CANCELLED,stored.status);assertNotNull(stored.finishedAt);
  assertEquals(next.id,runner.claim().id);
  assertFalse(runner.cancel(cancelled.id));
 }
 @Test void dispatcherKillsRunningBuildOnceItsCancellationIsRequested(){
  List<BuildRunner.Running> killed=new CopyOnWriteArrayList<>();
  BuildRunner worker=new BuildRunner(){@Override void kill(Running r){killed.add(r);r.cancelled=true;}};
  worker.logs=logs;worker.concurrency=1;worker.queueLimit=100;worker.recover(null);
  try{
   Build b=submit(),other=submit();runner.concurrency=2;runner.claim();runner.claim();
   BuildRunner.Running handle=new BuildRunner.Running(),untouched=new BuildRunner.Running();
   worker.running.put(b.id,handle);worker.running.put(other.id,untouched);
   worker.dispatch();assertTrue(killed.isEmpty());
   assertTrue(runner.cancel(b.id));
   assertEquals(Build.Status.RUNNING,read(b.id).status);assertTrue(read(b.id).cancelRequested);
   worker.dispatch();assertEquals(List.of(handle),killed);
   worker.dispatch();assertEquals(1,killed.size(),"a handle is killed once");
  }finally{worker.stop();}
 }
 @Test void cancellationRequestedBeforeCompletionWins(){
  Build b=submit();runner.claim();
  assertTrue(runner.cancel(b.id));
  runner.complete(new BuildRunner.Completion(b.id,Build.Status.PASSED,"revision",0,null,false,java.time.Instant.now()));
  Build stored=read(b.id);
  assertEquals(Build.Status.CANCELLED,stored.status);assertNotNull(stored.finishedAt);
  assertFalse(runner.cancel(b.id));
 }
 @Test void completionWithoutCancellationKeepsItsStatus(){
  Build b=submit();runner.claim();
  runner.complete(new BuildRunner.Completion(b.id,Build.Status.FAILED,"revision",1,null,false,java.time.Instant.now()));
  assertEquals(Build.Status.FAILED,read(b.id).status);
 }
 @Test void processStartedAfterKillIsStopped()throws Exception{
  BuildRunner.Running handle=new BuildRunner.Running();
  runner.kill(handle);
  long start=System.nanoTime();
  var r=BuildRunner.stream(List.of("sleep","60"),30,(bytes,off,len)->{},Long.MAX_VALUE,p->runner.started(handle,p));
  assertFalse(r.timeout());
  assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(15),"the process is stopped as soon as it starts");
 }
 @Test void killStopsRunningProcess()throws Exception{
  BuildRunner.Running handle=new BuildRunner.Running();
  CountDownLatch published=new CountDownLatch(1);
  Thread stopper=Thread.ofPlatform().start(()->{try{if(published.await(10,TimeUnit.SECONDS))runner.kill(handle);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
  long start=System.nanoTime();
  var r=BuildRunner.stream(List.of("sleep","60"),30,(bytes,off,len)->{},Long.MAX_VALUE,p->{runner.started(handle,p);published.countDown();});
  stopper.join();
  assertFalse(r.timeout());
  assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(15));
 }
 @Test void retentionPurgesCancelledBuilds(){
  Build b=submit();assertTrue(runner.cancel(b.id));
  retention.purge(java.time.Instant.now(),0,1);
  assertNotNull(read(b.id),"the newest build is kept");
  submit();Build newer=submit();assertTrue(runner.cancel(newer.id));
  retention.purge(java.time.Instant.now(),0,1);
  assertNull(read(b.id));assertNotNull(read(newer.id));
 }
}
