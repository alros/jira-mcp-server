package eu.rossotto;

/** A failure worth showing verbatim to the model (bad JQL, auth problems, missing config...). */
public class JiraException extends RuntimeException {

    private final int status;

    public JiraException(String message) {
        this(0, message);
    }

    public JiraException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
