package restaurant;

final class RestaurantException extends Exception {
    private final int status;

    RestaurantException(int status, String message) {
        super(message);
        this.status = status;
    }

    int status() {
        return status;
    }
}
