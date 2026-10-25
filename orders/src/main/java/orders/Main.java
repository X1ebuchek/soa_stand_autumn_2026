package orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Config cfg = Config.fromEnv();
        Store store = Store.connect(cfg);

        Worker worker = new Worker(store, cfg);
        Outbox outbox = Outbox.create(store, cfg);
        Api api = new Api(store, cfg);

        worker.start();
        if (outbox != null) {
            outbox.start();
        }
        api.start();

        log.info("orders слушает порт {}", cfg.port());
    }
}
