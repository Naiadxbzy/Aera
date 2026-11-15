package com.changyu.ochat;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.changyu.ochat.GenerationNetworkState.State.*;

public class GenerationNetworkStateTest {
 @Test public void lateLossAndCapabilitiesFromOldNetworkCannotPauseNewNetwork(){
  GenerationNetworkState state=new GenerationNetworkState();state.available("wifi");state.capabilities("wifi",true,true,false);
  state.available("cell");assertEquals(CHECKING,state.state());assertTrue(GenerationNetworkState.mustWait(state.state()));
  state.capabilities("cell",true,true,false);state.lost("wifi");state.blocked("wifi",true);state.capabilities("wifi",false,false,true);
  assertEquals(CONNECTED,state.state());assertFalse(GenerationNetworkState.mustWait(state.state()));
 }
 @Test public void blockedNetworkWaitsUntilSystemUnblocksIt(){
  GenerationNetworkState state=new GenerationNetworkState();state.available("wifi");state.capabilities("wifi",true,true,false);
  state.blocked("wifi",true);state.capabilities("wifi",true,true,false);assertEquals(BLOCKED,state.state());
  state.available("wifi");assertEquals(BLOCKED,state.state());state.blocked("wifi",false);assertEquals(CONNECTED,state.state());
 }
 @Test public void disconnectedNetworkWaitsAndReconnectUsesNewIdentity(){
  GenerationNetworkState state=new GenerationNetworkState();state.initialOffline();assertEquals(OFFLINE,state.state());
  state.available("first");state.capabilities("first",true,true,false);state.lost("first");assertEquals(OFFLINE,state.state());
  state.available("second");state.capabilities("first",true,true,false);assertEquals(CHECKING,state.state());
  state.capabilities("second",true,true,false);assertEquals(CONNECTED,state.state());
 }
 @Test public void portalWaitsUntilLoginCompletes(){
  GenerationNetworkState state=new GenerationNetworkState();state.available("portal");state.capabilities("portal",true,false,true);
  assertEquals(CAPTIVE,state.state());assertTrue(GenerationNetworkState.mustWait(state.state()));
  state.capabilities("portal",true,true,false);assertEquals(CONNECTED,state.state());
 }
 @Test public void unvalidatedVpnCanStillSendAndObservationFailureDoesNotBlock(){
  GenerationNetworkState state=new GenerationNetworkState();assertFalse(GenerationNetworkState.mustWait(state.state()));
  state.available("vpn");state.capabilities("vpn",true,false,false);
  assertEquals(UNVALIDATED,state.state());assertFalse(GenerationNetworkState.mustWait(state.state()));
 }
}
