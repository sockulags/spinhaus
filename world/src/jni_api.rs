//! JNI entry points for se.spinhaus.app.world.WorldNative.

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jbyteArray, jdouble, jint, jstring};
use jni::JNIEnv;

fn throw(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", msg);
}

fn string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_se_spinhaus_app_world_WorldNative_prepare<'l>(
    mut env: JNIEnv<'l>, _c: JClass<'l>, gpx: JString<'l>, name: JString<'l>, key: JString<'l>, cache: JString<'l>, online: jboolean, hour: jdouble, view_chunks: jint,
) -> jstring {
    let gpx = string(&mut env, &gpx);
    let name = string(&mut env, &name);
    let key = string(&mut env, &key);
    let cache = string(&mut env, &cache);
    let result = std::panic::catch_unwind(|| {
        crate::prepare(crate::PrepareOptions {
            gpx: if gpx.is_empty() { None } else { Some(&gpx) },
            name: &name,
            route_key: &key,
            cache_dir: std::path::Path::new(&cache),
            online: online != 0,
            hour,
            view_chunks,
        })
    });
    match result {
        Ok(Ok(json)) => env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Ok(Err(e)) => {
            throw(&mut env, &e);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(&mut env, "world generation crashed");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_se_spinhaus_app_world_WorldNative_blob<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, name: JString<'l>, a: jdouble, b: jdouble) -> jbyteArray {
    let name = string(&mut env, &name);
    match std::panic::catch_unwind(|| crate::blob(&name, a, b)) {
        Ok(Ok(bytes)) => env.byte_array_from_slice(&bytes).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()),
        Ok(Err(e)) => {
            throw(&mut env, &e);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(&mut env, "mesh generation crashed");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_se_spinhaus_app_world_WorldNative_planClassic<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, id: JString<'l>) -> jstring {
    let id = string(&mut env, &id);
    match crate::plan_classic(&id) {
        Ok(gpx) => env.new_string(gpx).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(e) => {
            throw(&mut env, &e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_se_spinhaus_app_world_WorldNative_release<'l>(_env: JNIEnv<'l>, _c: JClass<'l>) {
    crate::release();
}

#[no_mangle]
pub extern "system" fn Java_se_spinhaus_app_world_WorldNative_drainLog<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>) -> jstring {
    let text = crate::drain_log().join("\n");
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
