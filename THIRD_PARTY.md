# Сторонние компоненты и источники

Новый код опроса, сервиса, клиента и схемы находятся в этом архиве. Компоненты других авторов сохраняют свои лицензии и авторство.

| Компонент | Версия | Использование | Источник |
|---|---|---|---|
| jSerialComm, Fazecast / Will Hedgecock | 2.11.4 | Включён в `dist/mercury-pi.jar` без изменения класса/нативных библиотек | https://github.com/Fazecast/jSerialComm |
| Arduino AVR Boards / ArduinoCore-avr | 1.8.6 | Основа сборки прошивки, включая SoftwareSerial и SPI | https://github.com/arduino/ArduinoCore-avr |
| Arduino Ethernet | 2.0.2 | Сеть W5500 | https://github.com/arduino-libraries/Ethernet |
| JUnit Jupiter | 5.11.4 | Только тесты; в рабочий JAR не входит | https://github.com/junit-team/junit5 |
| Wiren Board wb-mqtt-serial | Публичный контрольный кадр | Независимые значения для тестирования протокола; код драйвера не копировался | https://github.com/wirenboard/wb-mqtt-serial |

jSerialComm опубликован с выбором LGPLv3 или Apache-2.0. Для включённой копии используется вариант Apache-2.0; текст лицензии и исходное NOTICE сохранены в `third-party/jSerialComm/`. Манифест итогового JAR заменён стандартным Maven Shade для запуска приложения, файлы библиотеки не модифицировались.

Для Arduino предоставлены все исходники прикладной программы и точные версии/команды повторной сборки, позволяющие пересобрать прошивку с изменёнными библиотеками. Исходники Arduino core/Ethernet загружаются штатно через Arduino IDE, CLI или PlatformIO; лицензии отдельных исходных файлов и платформы доступны в указанных upstream-проектах.

