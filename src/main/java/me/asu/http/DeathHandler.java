package me.asu.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

/**
 * 仅当发生不幸事件时，才应调用死亡处理程序。
 * <p>
 * 死亡处理程序用于后端，以便在所有其他处理程序未能执行任务时，
 * 向浏览器发送500错误消息。而在正常情况下，这种情况不应发生。
 * <p>
 * 此外，它还被设置为初始通配符处理程序，
 * 这意味着如果没有其他可用的处理程序，它将被使用。
 */
public class DeathHandler implements ContextHandler {
    public static ArrayList<String> errorMessages;
    private int code;

    /**
     * Creates a new DeathHandler...
     */
    public DeathHandler() {
        this(500);
    }


    public DeathHandler(int statusCode) {
        super();
        setupErrorMessages();
        code = statusCode;
    }

    @Override
    public int serve(Request req, Response resp) throws IOException {
        String message = errorMessages.get(new Random().nextInt(errorMessages.size()));
        resp.send(code, message);
        return 0;
    }

    /**
     * Setup error messages that could be sent to the client
     * 有趣的句子。
     */
    private static void setupErrorMessages() {
        errorMessages = new ArrayList<String>();

        errorMessages.add("Well, that went well...");
        errorMessages.add("That's not a good sound.");
        errorMessages.add("Oh God, oh God, we're all gonna die.");
        errorMessages.add("What a crazy random happenstance!");
        errorMessages.add("Uh, everything's under control. Situation normal.");
        errorMessages.add("Uh, we had a slight weapons malfunction, but, uh... "
                + "everything's perfectly all right now. We're fine. We're all "
                + "fine here now, thank you. How are you?");
        errorMessages.add("Definitely feeling aggressive tendency, sir!");
        errorMessages.add("If they move, shoot 'em.");

    }
}
