//! The few JNI calls we need, bound by hand (no `jni`/`jni-sys` crate: they pull in `std`). Function indices are
//! the fixed slots of the JNI function table in the JNI specification (chapter 4, "Interface Function Table").

#![allow(non_camel_case_types)]

use core::ffi::{c_char, c_void};

pub type jint = i32;
pub type jlong = i64;
pub type jsize = jint;
pub type jobject = *mut c_void;
pub type jclass = jobject;
pub type jstring = jobject;
pub type jbyteArray = jobject;

/// `JNIEnv*` in C: a pointer to a pointer to the function table.
pub type JNIEnv = *const *const c_void;

const NEW_STRING_UTF: usize = 167;
const GET_ARRAY_LENGTH: usize = 171;
const GET_BYTE_ARRAY_REGION: usize = 200;
const GET_DIRECT_BUFFER_ADDRESS: usize = 230;
const GET_DIRECT_BUFFER_CAPACITY: usize = 231;

unsafe fn slot(env: *mut JNIEnv, i: usize) -> *const c_void {
    if env.is_null() || (*env).is_null() {
        return core::ptr::null();
    }
    *(*env).add(i)
}

pub unsafe fn valid(env: *mut JNIEnv) -> bool {
    !env.is_null() && !(*env).is_null()
}

pub unsafe fn new_string_utf(env: *mut JNIEnv, s: *const c_char) -> jstring {
    let f = slot(env, NEW_STRING_UTF);
    if f.is_null() {
        return core::ptr::null_mut();
    }
    let f: unsafe extern "system" fn(*mut JNIEnv, *const c_char) -> jstring = core::mem::transmute(f);
    f(env, s)
}

pub unsafe fn array_length(env: *mut JNIEnv, a: jobject) -> Option<usize> {
    let f = slot(env, GET_ARRAY_LENGTH);
    if f.is_null() || a.is_null() {
        return None;
    }
    let f: unsafe extern "system" fn(*mut JNIEnv, jobject) -> jsize = core::mem::transmute(f);
    let n = f(env, a);
    if n < 0 { None } else { Some(n as usize) }
}

pub unsafe fn byte_array_region(env: *mut JNIEnv, a: jbyteArray, len: usize, out: *mut u8) -> bool {
    let f = slot(env, GET_BYTE_ARRAY_REGION);
    if f.is_null() {
        return false;
    }
    let f: unsafe extern "system" fn(*mut JNIEnv, jbyteArray, jsize, jsize, *mut i8) = core::mem::transmute(f);
    f(env, a, 0, len as jsize, out as *mut i8);
    true
}

pub unsafe fn direct_buffer<'a>(env: *mut JNIEnv, b: jobject) -> Option<&'a mut [u8]> {
    let (fa, fc) = (slot(env, GET_DIRECT_BUFFER_ADDRESS), slot(env, GET_DIRECT_BUFFER_CAPACITY));
    if fa.is_null() || fc.is_null() || b.is_null() {
        return None;
    }
    let fa: unsafe extern "system" fn(*mut JNIEnv, jobject) -> *mut c_void = core::mem::transmute(fa);
    let fc: unsafe extern "system" fn(*mut JNIEnv, jobject) -> jlong = core::mem::transmute(fc);
    let p = fa(env, b) as *mut u8;
    let n = fc(env, b);
    if p.is_null() || n <= 0 {
        return None;
    }
    Some(core::slice::from_raw_parts_mut(p, n as usize))
}
