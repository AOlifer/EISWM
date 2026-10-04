package com.eiswm;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Доступ к сервису машины (BwCarService, пакет com.bw.car) через общую библиотеку прошивки
 * bw.car.proxy (/system/framework/BwCarProxy.jar, подключается в манифесте через uses-library).
 * Библиотеки нет ни в SDK, ни в репозитории, поэтому классы bw.car.* берутся через reflection.
 * Без библиотеки (эмулятор, другая прошивка) {@link #isAvailable()} возвращает false.
 *
 * Только чтение и подписка на изменения: методов записи в машину здесь нет намеренно.
 */
final class CarApi {
    /** Описание свойства из getPropertyList(). */
    static final class PropertyConfig {
        int id, access, changeMode;
        String type;
        int[] areas;
        float minRate, maxRate;
    }

    /** Новое значение свойства. */
    interface PropertyListener {
        void onChange(int id, int area, int status, Object value);

        void onError(int id, int area);
    }

    interface PowerListener {
        void onPowerStateChanged(int state);
    }

    private static final String CAR = "bw.car.Car";
    private static final String PROPERTY_LISTENER = "bw.car.hardware.property.CarPropertyManager$CarPropertyEventListener";
    private static final String POWER_LISTENER = "bw.car.power.CarPowerManager$CarPowerStateListener";

    private final Context context;
    private Object car;

    CarApi(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Есть ли в прошивке библиотека bw.car.proxy. */
    static boolean isAvailable() {
        try {
            Class.forName(CAR);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Подключиться к сервису машины.
     * @param onConnected вызывается в потоке handler, когда сервис подключён
     * @param onDisconnected вызывается, если сервис машины упал; Car сам переподключится
     */
    void connect(Handler handler, Runnable onConnected, Runnable onDisconnected) throws Exception {
        ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                onConnected.run();
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                onDisconnected.run();
            }
        };
        Class<?> c = Class.forName(CAR);
        car = c.getMethod("createCar", Context.class, ServiceConnection.class, Handler.class)
                .invoke(null, context, conn, handler);
        c.getMethod("connect").invoke(car);
    }

    void disconnect() {
        if (car == null) return;
        try {
            car.getClass().getMethod("disconnect").invoke(car);
        } catch (Throwable ignored) {
        }
        car = null;
    }

    boolean isConnected() {
        try {
            return car != null && (Boolean) car.getClass().getMethod("isConnected").invoke(car);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Менеджер по имени: "property", "power", "car_setting" и др.; null, если не получилось. */
    Object manager(String name) {
        try {
            return car.getClass().getMethod("getCarManager", String.class).invoke(car, name);
        } catch (Throwable e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Свойства

    List<PropertyConfig> propertyList() throws Exception {
        Object pm = manager("property");
        List<PropertyConfig> out = new ArrayList<>();
        if (pm == null) return out;
        List<?> list = (List<?>) pm.getClass().getMethod("getPropertyList").invoke(pm);
        if (list == null) return out;
        for (Object cfg : list) {
            PropertyConfig p = new PropertyConfig();
            p.id = (Integer) call(cfg, "getPropertyId");
            p.access = intOr(call(cfg, "getAccess"), -1);
            p.changeMode = intOr(call(cfg, "getChangeMode"), -1);
            Object type = call(cfg, "getPropertyType");
            p.type = type instanceof Class ? ((Class<?>) type).getSimpleName() : String.valueOf(type);
            Object areas = call(cfg, "getAreaIds");
            p.areas = areas instanceof int[] ? (int[]) areas : new int[]{0};
            if (p.areas.length == 0) p.areas = new int[]{0};
            p.minRate = floatOr(call(cfg, "getMinSampleRate"));
            p.maxRate = floatOr(call(cfg, "getMaxSampleRate"));
            out.add(p);
        }
        return out;
    }

    /** Текущее значение: [status, value]. Бросает исключение, если машина не ответила. */
    Object[] getProperty(int id, int area) throws Exception {
        Object pm = manager("property");
        if (pm == null) throw new IllegalStateException("no property manager");
        Object v = pm.getClass().getMethod("getProperty", int.class, int.class).invoke(pm, id, area);
        if (v == null) return new Object[]{-1, null};
        return new Object[]{intOr(call(v, "getStatus"), -1), call(v, "getValue")};
    }

    /** Подписка на изменения свойства. Возвращает объект для {@link #unregister}, или null. */
    Object registerProperty(int id, float rate, PropertyListener l) {
        Object pm = manager("property");
        if (pm == null) return null;
        try {
            Class<?> li = Class.forName(PROPERTY_LISTENER);
            Object proxy = Proxy.newProxyInstance(li.getClassLoader(), new Class<?>[]{li},
                    handler((m, args) -> {
                        if (m.getName().equals("onChangeEvent") && args != null && args[0] != null) {
                            Object v = args[0];
                            l.onChange(intOr(call(v, "getPropertyId"), id), intOr(call(v, "getAreaId"), 0),
                                    intOr(call(v, "getStatus"), -1), call(v, "getValue"));
                        } else if (m.getName().equals("onErrorEvent") && args != null) {
                            l.onError((Integer) args[0], (Integer) args[1]);
                        }
                    }));
            Method reg = pm.getClass().getMethod("registerListener", li, int.class, float.class);
            return Boolean.TRUE.equals(reg.invoke(pm, proxy, id, rate)) ? proxy : null;
        } catch (Throwable e) {
            return null;
        }
    }

    void unregister(Object propertyListener) {
        Object pm = manager("property");
        if (pm == null || propertyListener == null) return;
        try {
            Class<?> li = Class.forName(PROPERTY_LISTENER);
            pm.getClass().getMethod("unregisterListener", li).invoke(pm, propertyListener);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- Питание

    boolean registerPower(PowerListener l) {
        Object pwr = manager("power");
        if (pwr == null) return false;
        try {
            Class<?> li = Class.forName(POWER_LISTENER);
            Object proxy = Proxy.newProxyInstance(li.getClassLoader(), new Class<?>[]{li},
                    handler((m, args) -> {
                        if (m.getName().equals("onPowerStateChanged") && args != null) {
                            l.onPowerStateChanged((Integer) args[0]);
                        }
                    }));
            pwr.getClass().getMethod("registerPowerStateListener", li).invoke(pwr, proxy);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Вызвать метод-геттер менеджера без аргументов; при ошибке — текст ошибки. */
    String query(String managerName, String method) {
        Object m = manager(managerName);
        if (m == null) return "no manager";
        try {
            return String.valueOf(m.getClass().getMethod(method).invoke(m));
        } catch (Throwable e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return "error: " + cause.getClass().getSimpleName();
        }
    }

    // ---------------------------------------------------------------- Вспомогательное

    private interface Callback {
        void on(Method m, Object[] args);
    }

    /** Обработчик прокси: методы Object отвечают сами, остальное — в callback. */
    private static InvocationHandler handler(Callback cb) {
        return (proxy, m, args) -> {
            switch (m.getName()) {
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "EISWM listener";
            }
            try {
                cb.on(m, args);
            } catch (Throwable ignored) {
            }
            return null;
        };
    }

    private static Object call(Object o, String method) {
        try {
            return o.getClass().getMethod(method).invoke(o);
        } catch (Throwable e) {
            return null;
        }
    }

    private static int intOr(Object o, int def) {
        return o instanceof Integer ? (Integer) o : def;
    }

    private static float floatOr(Object o) {
        return o instanceof Float ? (Float) o : 0f;
    }
}
