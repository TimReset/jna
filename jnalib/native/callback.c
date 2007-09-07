/* Copyright (c) 2007 Timothy Wall, All Rights Reserved
 * Copyright (c) 2007 Wayne Meissner, All Rights Reserved
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * <p/>
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.  
 */

#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <jni.h>

#if !defined(_WIN32)
#  include <sys/types.h>
#  include <sys/param.h>
#  if defined(__linux__)
#    include <sys/user.h> /* for PAGE_SIZE */
#  endif
#  include <sys/mman.h>
#  ifdef sun
#    include <sys/sysmacros.h>
#  endif
#  include "queue.h"
#  include <pthread.h>
#  define MMAP_CLOSURE
#endif
#include "dispatch.h"

#ifdef __cplusplus
extern "C" {
#endif

static ffi_type* get_ffi_type(char jtype);
static ffi_type* get_ffi_rtype(char jtype);
static void callback_dispatch(ffi_cif*, void*, void**, void*);
static ffi_closure* alloc_closure(JNIEnv *env);
static void free_closure(JNIEnv* env, ffi_closure *closure);

#ifdef MMAP_CLOSURE
static pthread_mutex_t closure_lock;
static LIST_HEAD(closure_list, closure) closure_list;
#endif

static jclass classObject;

callback*
create_callback(JNIEnv* env, jobject obj, jobject method,
                jobjectArray param_types, jclass return_type,
                callconv_t calling_convention) {
  callback* cb;
  ffi_abi abi = FFI_DEFAULT_ABI;
  int args_size = 0;
  jsize argc;
  JavaVM* vm;
  int i;

  if ((*env)->GetJavaVM(env, &vm) != JNI_OK) {
    throwByName(env, "java/lang/UnsatisfiedLinkError",
                "Can't get Java VM");
    return NULL;
  }
  argc = (*env)->GetArrayLength(env, param_types);
  cb = (callback *)malloc(sizeof(callback));
  cb->ffi_closure = alloc_closure(env);
  cb->object = (*env)->NewWeakGlobalRef(env, obj);
  cb->methodID = (*env)->FromReflectedMethod(env, method);
  cb->vm = vm;
 
  for (i=0;i < argc;i++) {
    jclass cls = (*env)->GetObjectArrayElement(env, param_types, i);
    cb->param_jtypes[i] = get_jtype(env, cls);
    cb->ffi_args[i] = get_ffi_type(cb->param_jtypes[i]);
  }

#ifdef _WIN32
  if (calling_convention == CALLCONV_STDCALL) {
    abi = FFI_STDCALL;
  }
#endif // _WIN32

  ffi_prep_cif(&cb->ffi_cif, abi, argc,
               get_ffi_rtype(get_jtype(env, return_type)),
               &cb->ffi_args[0]);
  ffi_prep_closure(cb->ffi_closure, &cb->ffi_cif, callback_dispatch, cb);

  return cb;
}
void 
free_callback(JNIEnv* env, callback *cb) {
  (*env)->DeleteWeakGlobalRef(env, cb->object);
  free_closure(env, cb->ffi_closure);
  free(cb);
}

static ffi_type*
get_ffi_type(char jtype) {
  switch (jtype) {
  case 'Z': 
    return &ffi_type_sint;
  case 'B':
    return &ffi_type_sint8;
  case 'C':
    return &ffi_type_sint;
  case 'S':
    return &ffi_type_sshort;
  case 'I':
    return &ffi_type_sint;
  case 'J':
    return &ffi_type_sint64;
  case 'F':
    return &ffi_type_float;
  case 'D':
    return &ffi_type_double;
  case 'V':
    return &ffi_type_void;
  case 'L':
  default:
    return &ffi_type_pointer;
  }
}
static ffi_type*
get_ffi_rtype(char jtype) {
  switch (jtype) {
  case 'Z': 
  case 'B': 
  case 'C': 
  case 'S':    
  case 'I':
    /*
     * Always use a return type the size of a cpu register.  This fixes up
     * callbacks on big-endian 64bit machines, and does not break things on
     * i386 or amd64. 
     */
    return &ffi_type_slong;
  case 'J':
    return &ffi_type_sint64;
  case 'F':
    return &ffi_type_float;
  case 'D':
    return &ffi_type_double;
  case 'V':
    return &ffi_type_void;
  case 'L':
  default:
    return &ffi_type_pointer;
  }
}
  
static void
callback_dispatch(ffi_cif* cif, void* resp, void** cbargs, void* user_data) {
  callback* cb = (callback *) user_data;
  JavaVM* jvm = cb->vm;
  jobject obj;
  JNIEnv* env;
  int attached;
  unsigned int i;
  jobjectArray array;
  
  attached = (*jvm)->GetEnv(jvm, (void *)&env, JNI_VERSION_1_4) == JNI_OK;
  if (!attached) {
    if ((*jvm)->AttachCurrentThread(jvm, (void *)&env, NULL) != JNI_OK) {
      fprintf(stderr, "JNA: Can't attach to current thread\n");
      return;
    }
  }
  
  obj = (*env)->NewLocalRef(env, cb->object);
  array = (*env)->NewObjectArray(env, cif->nargs, classObject, NULL);
  for (i=0;i < cif->nargs;i++) {
    jobject obj = new_object(env, cb->param_jtypes[i], cbargs[i]);
    (*env)->SetObjectArrayElement(env, array, i, obj);
  }
  
  // Avoid calling back to a GC'd object
  if ((*env)->IsSameObject(env, obj, NULL)) {
    fprintf(stderr, "JNA: callback object has been garbage collected\n");
    memset(resp, 0, cif->rtype->size); 
  }
  else {
    jobject ret = (*env)->CallObjectMethod(env, obj, cb->methodID, array);
    if ((*env)->ExceptionCheck(env)) {
      fprintf(stderr, "JNA: uncaught exception in callback\n");
      memset(resp, 0, cif->rtype->size);
    }
    else {
      extract_value(env, ret, resp);
    }
  }

  if (!attached) {
    (*jvm)->DetachCurrentThread(jvm);
  }
}

jboolean 
jnidispatch_callback_init(JNIEnv* env) {

  if (!LOAD_CREF(env, Object, "java/lang/Object")) return JNI_FALSE;

#ifdef MMAP_CLOSURE
  /*
   * Create the lock for the mmap arena
   */
  pthread_mutex_init(&closure_lock, NULL);
  LIST_INIT(&closure_list);
#endif

  return JNI_TRUE;
}
  
// Use mmap for closure memory, if available
#ifdef MMAP_CLOSURE
# ifndef PAGE_SIZE
#  if defined(PAGESIZE)
#   define PAGE_SIZE PAGESIZE
#  elif defined(NBPG)
#   define PAGE_SIZE NBPG
#  endif   
# endif
typedef struct closure {
  LIST_ENTRY(closure) list;
} closure;

static ffi_closure*
alloc_closure(JNIEnv* env)
{
  closure* closure = NULL;
  pthread_mutex_lock(&closure_lock);
  
  if (closure_list.lh_first == NULL) {
    /*
     * Get a new page from the kernel and divvy that up
     */
    int clsize = roundup(sizeof(ffi_closure), sizeof(void *));
    int i;
    caddr_t ptr = mmap(0, PAGE_SIZE, PROT_EXEC | PROT_READ | PROT_WRITE,
                       MAP_ANON | MAP_PRIVATE, -1, 0);
    if (ptr == NULL) {
      pthread_mutex_unlock(&closure_lock);
      return NULL;
    }
    for (i = 0; i <= (int)(PAGE_SIZE - clsize); i += clsize) {
      closure = (struct closure *)(ptr + i);
      LIST_INSERT_HEAD(&closure_list, closure, list);            
    }
  }
  closure = closure_list.lh_first;
  LIST_REMOVE(closure, list);
  
  pthread_mutex_unlock(&closure_lock);
  memset(closure, 0, sizeof(*closure));
  return (ffi_closure *)closure;
}
  
static void
free_closure(JNIEnv* env, ffi_closure *ffi_closure) 
{
  pthread_mutex_lock(&closure_lock);    
  LIST_INSERT_HEAD(&closure_list, (closure*)ffi_closure, list);
  pthread_mutex_unlock(&closure_lock);
}
#else
static ffi_closure*
alloc_closure(JNIEnv* env)
{
  return (ffi_closure *)calloc(1, sizeof(ffi_closure));
}
static void
free_closure(JNIEnv* env, ffi_closure *closure) 
{
  free(closure);
}
#endif

#ifdef __cplusplus
}
#endif