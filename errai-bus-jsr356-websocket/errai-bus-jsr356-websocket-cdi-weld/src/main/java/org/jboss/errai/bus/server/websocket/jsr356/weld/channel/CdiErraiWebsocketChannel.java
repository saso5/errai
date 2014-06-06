package org.jboss.errai.bus.server.websocket.jsr356.weld.channel;

import org.jboss.errai.bus.server.websocket.jsr356.channel.DefaultErraiWebsocketChannel;
import org.jboss.errai.bus.server.websocket.jsr356.weld.ScopeAdapter;
import org.jboss.errai.bus.server.websocket.jsr356.weld.conversation.ConversationScopeAdapter;
import org.jboss.errai.bus.server.websocket.jsr356.weld.conversation.ConversationState;
import org.jboss.errai.bus.server.websocket.jsr356.weld.conversation.WeldConversationScopeAdapter;
import org.jboss.errai.bus.server.websocket.jsr356.weld.request.WeldRequestScopeAdapter;
import org.jboss.errai.bus.server.websocket.jsr356.weld.session.SessionScopeAdapter;
import org.jboss.errai.bus.server.websocket.jsr356.weld.session.WeldSessionScopeAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpSession;
import javax.websocket.Session;

/**
 * Cdi version of {@link DefaultErraiWebsocketChannel}
 * 
 * @author : Michel Werren
 */
public class CdiErraiWebsocketChannel extends DefaultErraiWebsocketChannel {

  private static final Logger LOGGER = LoggerFactory
          .getLogger(CdiErraiWebsocketChannel.class.getName());

  private final SessionScopeAdapter sessionScopeAdapter;

  private final ScopeAdapter requestScopeAdapter;

  private final ConversationScopeAdapter conversationScopeAdapter;

  private final ConversationState conversationState = new ConversationState();

  public CdiErraiWebsocketChannel(Session session, HttpSession httpSession) {
    super(session, httpSession);

    sessionScopeAdapter = WeldSessionScopeAdapter.getInstance();
    requestScopeAdapter = WeldRequestScopeAdapter.getInstance();
    conversationScopeAdapter = WeldConversationScopeAdapter.getInstance();
  }

  public void doErraiMessage(final String message) {
    activateBuildinScopes();
    try {
      super.doErraiMessage(message);
    } finally {
      deactivateBuiltinScopes();
    }
  }

  /**
   * Deactivate builtin CDI scopes. Must be invoked after message processing.
   */
  private void deactivateBuiltinScopes() {
    conversationScopeAdapter.deactivateContext();
    sessionScopeAdapter.deactivateContext();
    // bean store should be destroyed after each message
    requestScopeAdapter.invalidateContext();
  }

  /**
   * Activate builtin CDI scopes
   */
  private void activateBuildinScopes() {
    requestScopeAdapter.activateContext();
    sessionScopeAdapter.activateContext(httpSession);
    conversationScopeAdapter.activateContext(conversationState);
  }

  /**
   * When this channel is closed, long running conversations has to be
   * invalidated.
   */
  public void onSessionClosed() {
    if (conversationState.isLongRunning()) {
      conversationScopeAdapter.activateContext(conversationState);
      conversationScopeAdapter.invalidateContext();
    }
  }
}
