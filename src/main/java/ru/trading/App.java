package ru.trading;

import com.sun.net.httpserver.HttpServer;
import ru.trading.domain.Instrument;
import ru.trading.http.Api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executors;

public final class App {

    public static final List<Instrument> INSTRUMENTS = List.of(
            Instrument.stock("SBER", "Сбербанк ао", 10, "0.01"),
            Instrument.stock("GAZP", "Газпром ао", 10, "0.01"),
            Instrument.stock("LKOH", "Лукойл ао", 1, "0.5"),
            Instrument.bond("SU26238RMFS4", "ОФЗ 26238", "0.001", "1000"),
            Instrument.currencyPair("CNYRUB_TOM", "Юань / рубль", 1000, "0.0001", "CNY", "RUB"));

    public static HttpServer start(Exchange exchange, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new Api(exchange));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        start(new Exchange(INSTRUMENTS), port);
        System.out.println("Trading system: http://localhost:" + port);
    }

    private App() {
    }
}
