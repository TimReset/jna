/* Copyright (c) 2007 Timothy Wall, All Rights Reserved
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.  
 */
package com.sun.jna;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import com.sun.jna.Library.Handler;

/** Provides a reference to an association between a native callback closure
 * and a Java {@link Callback} closure. 
 */

class CallbackReference extends WeakReference {
    
    private interface NativeFunctionProxy { }
    
    static final Map callbackMap = new WeakHashMap();
    static final Map altCallbackMap = new WeakHashMap();
    
    /** Return a CallbackReference associated with the given function pointer.
     * If the pointer refers to a Java callback trampoline, return the Java
     * callback.  Otherwise, return a proxy to the native function pointer.
     */
    public static Callback getCallback(Class type, Pointer p) {
        if (p != null) {
            if (!type.isInterface())
                throw new IllegalArgumentException("Callback type must be an interface");
            Map map = AltCallingConvention.class.isAssignableFrom(type)
                ? altCallbackMap : callbackMap;
            synchronized(map) {
                for (Iterator i=map.keySet().iterator();i.hasNext();) {
                    Callback cb = (Callback)i.next();
                    if (type.isAssignableFrom(cb.getClass())) {
                        CallbackReference cbref = (CallbackReference)map.get(cb);
                        Pointer cbp = cbref != null
                            ? cbref.getTrampoline() : getNativeFunctionPointer(cb);
                        if (p.equals(cbp)) {
                            return cb;
                        }
                    }
                }
                int ctype = AltCallingConvention.class.isAssignableFrom(type)
                    ? Function.ALT_CONVENTION : Function.C_CONVENTION;
                NativeFunctionHandler h = new NativeFunctionHandler(p, ctype);
                Callback cb = (Callback)Proxy.newProxyInstance(type.getClassLoader(), new Class[] { type, NativeFunctionProxy.class }, h);
                map.put(cb, null);
                return cb;
            }
        }
        return null;
    }
    
    Pointer cbstruct;
    // Keep a reference to avoid premature GC
    CallbackProxy proxy;
    private CallbackReference(Callback callback, int callingConvention) {
        super(callback);
        Class type = callback.getClass();
        Class[] ifaces = type.getInterfaces();
        for (int i=0;i < ifaces.length;i++) {
            if (Callback.class.isAssignableFrom(ifaces[i])) {
                type = ifaces[i];
                break;
            }
        }
        TypeMapper mapper = null;
        Class declaring = type.getDeclaringClass();
        if (declaring != null) {
            mapper = Native.getTypeMapper(declaring);
        }
        Method m = getCallbackMethod(callback);
        if (callback instanceof CallbackProxy) {
            proxy = (CallbackProxy)callback;
        }
        else {
            proxy = new DefaultCallbackProxy(m, mapper);
        }

        // Generate a list of parameter types that the native code can 
        // handle.  Let the CallbackProxy do any further conversion
        // to match the true Java callback method signature
        Class[] nativeParamTypes = proxy.getParameterTypes();
        Class returnType = proxy.getReturnType();
        if (mapper != null) {
            for (int i=0;i < nativeParamTypes.length;i++) {
                FromNativeConverter rc = mapper.getFromNativeConverter(nativeParamTypes[i]);
                if (rc != null) {
                    nativeParamTypes[i] = rc.nativeType();
                }
            }
            ToNativeConverter tn = mapper.getToNativeConverter(returnType);
            if (tn != null) {
                returnType = tn.nativeType();
            }
        }
        for (int i=0;i < nativeParamTypes.length;i++) {
            Class cls = nativeParamTypes[i];
            if (Structure.class.isAssignableFrom(cls)) {
                // Make sure we can instantiate an argument of this type
                try {
                    cls.newInstance();
                }
                catch (InstantiationException e) {
                    throw new IllegalArgumentException("Can't instantiate " + cls + ": " + e);
                }
                catch (IllegalAccessException e) {
                    throw new IllegalArgumentException("Instantiation of " + cls 
                                                       + " not allowed (is it public?): " + e);
                } 
                nativeParamTypes[i] = Pointer.class;
            }
            else if (NativeMapped.class.isAssignableFrom(cls)) {
                nativeParamTypes[i] = new NativeMappedConverter(cls).nativeType();
            }
            else if (cls == String.class || cls == WString.class) {
                nativeParamTypes[i] = Pointer.class;
            }
            else if (!isAllowableNativeType(cls)) {
                throw new IllegalArgumentException("Callback argument " + cls + " requires custom type conversion");
            }
        }

        Method proxyMethod = getCallbackMethod(proxy);
        cbstruct = createNativeCallback(proxy, proxyMethod,  
                                        nativeParamTypes, returnType,
                                        callingConvention);
    }
    
    private Method getCallbackMethod(Callback callback) {
        Method[] mlist = callback.getClass().getMethods();
        for (int mi=0;mi < mlist.length;mi++) {
            Method m = mlist[mi];
            if (Callback.METHOD_NAME.equals(m.getName())) {
                if (m.getParameterTypes().length > Function.MAX_NARGS) {
                    String msg = "Method signature exceeds the maximum "
                        + "parameter count: " + m;
                    throw new IllegalArgumentException(msg);
                }
                return m;
            }
        }
        String msg = "Callback must implement method named '"
            + Callback.METHOD_NAME + "'";
        throw new IllegalArgumentException(msg);
    }
    
    public Pointer getTrampoline() {
        return cbstruct.getPointer(0);
    }
    protected void finalize() {
        freeNativeCallback(cbstruct.peer);
        cbstruct.peer = 0;
    }
    
    private Callback getCallback() {
        return (Callback)get();
    }

    private static Pointer getNativeFunctionPointer(Callback cb) {
        if (cb instanceof NativeFunctionProxy) {
            NativeFunctionHandler handler = 
                (NativeFunctionHandler)Proxy.getInvocationHandler(cb);
            return handler.getPointer();
        }
        return null;
    }
    
    /** Return a {@link Pointer} to the native function address for the
     * given callback. 
     */
    public static Pointer getFunctionPointer(Callback cb) {
        Pointer fp = null;
        if (cb == null) return null;
        if ((fp = getNativeFunctionPointer(cb)) != null) {
            return fp;
        }
        int callingConvention = cb instanceof AltCallingConvention
            ? Function.ALT_CONVENTION : Function.C_CONVENTION;
        Map map = callingConvention == Function.ALT_CONVENTION
            ? altCallbackMap : callbackMap;
        synchronized(map) {
            CallbackReference cbref = (CallbackReference)map.get(cb);
            if (cbref == null) {
                cbref = new CallbackReference(cb, callingConvention);
                map.put(cb, cbref);
            }
            return cbref.getTrampoline();
        }
    }

    private class DefaultCallbackProxy implements CallbackProxy {
        private Method callbackMethod;
        private ToNativeConverter toNative;
        private FromNativeConverter[] fromNative;
        public DefaultCallbackProxy(Method callbackMethod, TypeMapper mapper) {
            this.callbackMethod = callbackMethod;
            Class[] argTypes = callbackMethod.getParameterTypes();
            Class returnType = callbackMethod.getReturnType();
            fromNative = new FromNativeConverter[argTypes.length];
            if (NativeMapped.class.isAssignableFrom(returnType)) {
                toNative = new NativeMappedConverter(returnType);
            }
            else if (mapper != null) {
                toNative = mapper.getToNativeConverter(returnType);
            }
            for (int i=0;i < fromNative.length;i++) {
                if (NativeMapped.class.isAssignableFrom(argTypes[i])) {
                    fromNative[i] = new NativeMappedConverter(argTypes[i]);
                }
                else if (mapper != null) {
                    fromNative[i] = mapper.getFromNativeConverter(argTypes[i]);
                }
            }
            if (!callbackMethod.isAccessible()) {
                try {
                    callbackMethod.setAccessible(true);
                }
                catch(SecurityException e) {
                    throw new IllegalArgumentException("Callback method is inaccessible, make sure the interface is public: " + callbackMethod);
                }
            }
        }
        /** Called from native code.  All arguments are in an array of 
         * Object as the first argument.  Converts all arguments to types
         * required by the actual callback method signature, and converts
         * the result back into an appropriate native type.
         * This method <em>must not</em> throw exceptions. 
         */
        public Object callback(Object[] args) {
            Class[] paramTypes = callbackMethod.getParameterTypes();
            Object[] callbackArgs = new Object[args.length];

            // convert basic supported types to appropriate Java parameter types
            for (int i=0;i < args.length;i++) {
                Class type = paramTypes[i];
                Object arg = args[i];
                if (fromNative[i] != null) {
                    FromNativeContext context = 
                        new CallbackInvocationContext(type, callbackMethod, args);
                    arg = fromNative[i].fromNative(arg, context);
                }
                callbackArgs[i] = convertArgument(arg, type);
            }

            Object result = null;
            Callback cb = getCallback();
            if (cb != null) {
                try {
                    result = convertResult(callbackMethod.invoke(cb, callbackArgs));
                }
                catch (IllegalArgumentException e) {
                    e.printStackTrace();
                }
                catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
                catch (InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
            return result;
        }

        /** Convert argument from its basic native type to the given
         * Java parameter type.
         */
        private Object convertArgument(Object value, Class dstType) {
            if (value instanceof Pointer) {
                if (dstType == String.class) {
                    value = ((Pointer)value).getString(0);
                }
                else if (dstType == WString.class) {
                    value = new WString(((Pointer)value).getString(0, true));
                }
                else if (Structure.class.isAssignableFrom(dstType)) {
                    Pointer p = (Pointer)value;
                    try {
                        Structure s = (Structure)dstType.newInstance();
                        s.useMemory(p);
                        s.read();
                        value = s;
                    }
                    catch(InstantiationException e) {
                        // can't happen, already checked for
                    }
                    catch(IllegalAccessException e) {
                        // can't happen, already checked for
                    }
                }
            }
            else if ((boolean.class == dstType || Boolean.class == dstType)
                     && value instanceof Number) {
                value = Boolean.valueOf(((Number)value).intValue() != 0);
            }
            return value;
        }
        
        private Object convertResult(Object value) {
            if (toNative != null) {
                value = toNative.toNative(value, new ToNativeContext());
            }
            if (value == null)
                return null;
            Class cls = value.getClass();
            if (Structure.class.isAssignableFrom(cls)) {
                return ((Structure)value).getPointer();
            }
            else if (cls == boolean.class || cls == Boolean.class) {
                return new Integer(Boolean.TRUE.equals(value)?-1:0);
            }
            else if (cls == String.class) {
                // FIXME: need to prevent GC, but how and for how long?
                return new NativeString(value.toString()).getPointer();
            }
            else if (cls == WString.class) {
                // FIXME: need to prevent GC, but how and for how long?
                return new NativeString(value.toString(), true).getPointer();
            }
            else if (Callback.class.isAssignableFrom(cls)) {
                return getFunctionPointer((Callback)value);
            }
            else if (!isAllowableNativeType(cls)) {
                throw new IllegalArgumentException("Return type " + cls + " will be ignored");
            }
            return value;
        }
        public Class[] getParameterTypes() {
            return callbackMethod.getParameterTypes();
        }
        public Class getReturnType() {
            return callbackMethod.getReturnType();
        }
    }

    /** Provide invocation handling for an auto-generated Java interface proxy 
     * for a native function pointer.
     */
    private static class NativeFunctionHandler implements InvocationHandler {
        private Function function;
        
        public NativeFunctionHandler(Pointer address, int callingConvention) {
            this.function = new Function(address, callingConvention);
        }
        
        /** Chain invocation to the native function. */
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (Library.Handler.OBJECT_TOSTRING.equals(method)) {
                return "Proxy interface to " + function;
            }
            else if (Library.Handler.OBJECT_HASHCODE.equals(method)) {
                return new Integer(hashCode());
            }
            else if (Library.Handler.OBJECT_EQUALS.equals(method)) {
                Object o = args[0];
                if (o != null && Proxy.isProxyClass(o.getClass())) {
                    Boolean.valueOf(Proxy.getInvocationHandler(o) == this);
                }
                return Boolean.FALSE;
            }
            if (Function.isVarArgs(method)) {
                args = Function.concatenateVarArgs(args);
            }
            return function.invoke(method.getReturnType(), args);
        }
        
        public Pointer getPointer() {
            return function;
        }
    }
    /** Returns whether the given class is supported in native code.
     * Other types (String, WString, Structure, arrays, NativeMapped,
     * etc) are supported in the Java library.
     */
    private static boolean isAllowableNativeType(Class cls) {
        return cls == boolean.class || cls == Boolean.class
            || cls == byte.class || cls == Byte.class
            || cls == short.class || cls == Short.class
            || cls == char.class || cls == Character.class
            || cls == int.class || cls == Integer.class
            || cls == long.class || cls == Long.class
            || cls == float.class || cls == Float.class
            || cls == double.class || cls == Double.class
            || Pointer.class.isAssignableFrom(cls);
    }
    
    /** Create a native trampoline to delegate execution to the Java callback. 
     */
    private static native Pointer createNativeCallback(CallbackProxy callback, 
                                                       Method method, 
                                                       Class[] parameterTypes,
                                                       Class returnType,
                                                       int callingConvention);
    /** Free the given callback trampoline. */
    private static native void freeNativeCallback(long ptr);
}