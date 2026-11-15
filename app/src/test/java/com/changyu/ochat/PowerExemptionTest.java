package com.changyu.ochat;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class PowerExemptionTest {
 static final class Shell implements PowerExemption.Command {
  boolean enabled,ignoreWrites;final List<String> operations=new ArrayList<>();
  public String run(String[] args){
   assertArrayEquals(new String[]{"/system/bin/dumpsys","deviceidle","whitelist",args[3]},args);
   assertEquals(PowerExemption.PACKAGE,args[3].substring(1));
   operations.add(args[3]);
   char operation=args[3].charAt(0);
   if(operation=='=')return String.valueOf(enabled)+"\n";
   if(!ignoreWrites)enabled=operation=='+';
   return "OK";
  }
 }
 @Test public void addsAndVerifiesOnlyOwnPackage()throws Exception{
  Shell shell=new Shell();assertEquals(PowerExemption.ADDED,PowerExemption.change(true,false,shell));
  assertTrue(shell.enabled);assertEquals(3,shell.operations.size());
  assertEquals("="+PowerExemption.PACKAGE,shell.operations.get(0));assertEquals("+"+PowerExemption.PACKAGE,shell.operations.get(1));
 }
 @Test public void existingExemptionIsNotClaimed()throws Exception{
  Shell shell=new Shell();shell.enabled=true;
  assertEquals(PowerExemption.UNCHANGED,PowerExemption.change(true,false,shell));assertEquals(1,shell.operations.size());
 }
 @Test public void unownedExemptionCannotBeRemoved()throws Exception{
  Shell shell=new Shell();shell.enabled=true;
  assertThrows(IOException.class,()->PowerExemption.change(false,false,shell));
  assertTrue(shell.enabled);assertEquals(1,shell.operations.size());
 }
 @Test public void removesAndVerifiesOwnedExemption()throws Exception{
  Shell shell=new Shell();shell.enabled=true;
  assertEquals(PowerExemption.REMOVED,PowerExemption.change(false,true,shell));assertFalse(shell.enabled);assertEquals(3,shell.operations.size());
 }
 @Test public void unchangedSystemStateIsNotReportedAsSuccess(){
  Shell shell=new Shell();shell.ignoreWrites=true;
  assertThrows(IOException.class,()->PowerExemption.change(true,false,shell));assertEquals(3,shell.operations.size());
 }
 @Test public void unknownReadbackCannotTriggerMutation(){
  List<String> operations=new ArrayList<>();
  assertThrows(IOException.class,()->PowerExemption.change(true,false,args->{operations.add(args[3]);return "Permission denial";}));
  assertEquals(1,operations.size());
 }
 @Test public void failedWriteIsNotReportedAsSuccess(){
  assertThrows(IOException.class,()->PowerExemption.change(true,false,args->{if(args[3].startsWith("="))return "false";throw new IOException("Denied");}));
 }
 @Test public void unsupportedOperationsAreRejected(){assertThrows(IllegalArgumentException.class,()->PowerExemption.command(';'));}
}
