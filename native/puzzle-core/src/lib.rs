// Native puzzle-math core for Twisted4D. The JNI boundary here is intentionally thin --
// it just marshals calls into `cube3`, which holds the actual puzzle logic.

mod cube3;

use std::sync::{Mutex, OnceLock};

use cube3::Cube3;
use jni::objects::JClass;
use jni::sys::{jfloatArray, jstring};
use jni::JNIEnv;

const CORE_VERSION: &str = env!("CARGO_PKG_VERSION");

fn cube() -> &'static Mutex<Cube3> {
    static CUBE: OnceLock<Mutex<Cube3>> = OnceLock::new();
    CUBE.get_or_init(|| Mutex::new(Cube3::solved()))
}

#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_coreVersion<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    env.new_string(CORE_VERSION)
        .expect("failed to allocate Java string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cubeReset<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    *cube().lock().unwrap() = Cube3::solved();
}

#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cubeTwistU<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    cube().lock().unwrap().twist_u();
}

#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cubeGetTransforms<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jfloatArray {
    let transforms = cube().lock().unwrap().transforms();
    let array = env
        .new_float_array(transforms.len() as i32)
        .expect("failed to allocate float array");
    env.set_float_array_region(&array, 0, &transforms)
        .expect("failed to fill float array");
    array.into_raw()
}
