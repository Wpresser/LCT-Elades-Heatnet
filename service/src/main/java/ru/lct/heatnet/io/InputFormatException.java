package ru.lct.heatnet.io;

/** Входной файл не соответствует ожидаемой структуре или превышает лимиты. */
public class InputFormatException extends RuntimeException {

    public InputFormatException(String message) {
        super(message);
    }
}
