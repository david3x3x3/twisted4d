// Native puzzle-math core for Twisted4D. The JNI boundary here is intentionally thin -- it
// just marshals calls into `cube3`/`cube4`, which hold the actual puzzle logic.

mod cube3;
mod cube4;

use std::sync::{Mutex, OnceLock};

use cube3::{Cube3, Face};
use cube4::{Axis4, Cell4, Cube4};
use jni::objects::{JClass, JIntArray};
use jni::sys::{jboolean, jfloatArray, jint, jintArray, jstring};
use jni::JNIEnv;

const CORE_VERSION: &str = env!("CARGO_PKG_VERSION");

fn cube3() -> &'static Mutex<Cube3> {
    static CUBE: OnceLock<Mutex<Cube3>> = OnceLock::new();
    CUBE.get_or_init(|| Mutex::new(Cube3::solved()))
}

fn cube4() -> &'static Mutex<Cube4> {
    static CUBE: OnceLock<Mutex<Cube4>> = OnceLock::new();
    CUBE.get_or_init(|| Mutex::new(Cube4::solved()))
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
    *cube3().lock().unwrap() = Cube3::solved();
}

#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cubeTwist<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    face: jint,
    prime: jboolean,
) {
    if let Some(face) = Face::from_index(face) {
        cube3().lock().unwrap().twist(face, prime != 0);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cubeIsSolved<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    if cube3().lock().unwrap().is_solved() {
        1
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cubeScramble<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    move_count: jint,
) {
    cube3().lock().unwrap().scramble(move_count.max(0) as u32);
}

#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cubeGetTransforms<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jfloatArray {
    let transforms = cube3().lock().unwrap().transforms();
    let array = env
        .new_float_array(transforms.len() as i32)
        .expect("failed to allocate float array");
    env.set_float_array_region(&array, 0, &transforms)
        .expect("failed to fill float array");
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cubeGetState<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jintArray {
    let state = cube3().lock().unwrap().get_state();
    let array = env
        .new_int_array(state.len() as i32)
        .expect("failed to allocate int array");
    env.set_int_array_region(&array, 0, &state)
        .expect("failed to fill int array");
    array.into_raw()
}

/// Replaces native state wholesale from a previously-[cubeGetState]-produced array (e.g.
/// restoring a puzzle saved before the process died). Silently ignored (leaving current state
/// untouched) if the array is the wrong length or otherwise unreadable.
#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cubeSetState<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    state: jintArray,
) {
    let state = unsafe { JIntArray::from_raw(state) };
    if let Ok(len) = env.get_array_length(&state) {
        let mut buf = vec![0i32; len as usize];
        if env.get_int_array_region(&state, 0, &mut buf).is_ok() {
            if let Some(cube) = Cube3::from_state(&buf) {
                *cube3().lock().unwrap() = cube;
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cube4Reset<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    *cube4().lock().unwrap() = Cube4::solved();
}

#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cube4Twist<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    cell: jint,
    fix_axis2: jint,
    prime: jboolean,
) {
    if let (Some(cell), Some(fix_axis2)) = (Cell4::from_index(cell), Axis4::from_index(fix_axis2)) {
        cube4().lock().unwrap().twist(cell, fix_axis2, prime != 0);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cube4IsSolved<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    if cube4().lock().unwrap().is_solved() {
        1
    } else {
        0
    }
}

/// Returns the moves actually applied, flattened as (cellIndex, axisIndex, primeFlag) triples --
/// the caller needs these (not just the resulting state) to record the scramble into its own
/// twist history, so exported logs can mark where the scramble ends (see MainActivity.mc4dLogFile).
#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cube4Scramble<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    move_count: jint,
) -> jintArray {
    let moves = cube4().lock().unwrap().scramble(move_count.max(0) as u32);
    let mut flat = Vec::with_capacity(moves.len() * 3);
    for (cell, axis, prime) in moves {
        flat.push(cell as i32);
        flat.push(axis.index() as i32);
        flat.push(prime as i32);
    }
    let array = env
        .new_int_array(flat.len() as i32)
        .expect("failed to allocate int array");
    env.set_int_array_region(&array, 0, &flat)
        .expect("failed to fill int array");
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cube4GetTransforms<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jfloatArray {
    let transforms = cube4().lock().unwrap().transforms();
    let array = env
        .new_float_array(transforms.len() as i32)
        .expect("failed to allocate float array");
    env.set_float_array_region(&array, 0, &transforms)
        .expect("failed to fill float array");
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cube4GetState<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jintArray {
    let state = cube4().lock().unwrap().get_state();
    let array = env
        .new_int_array(state.len() as i32)
        .expect("failed to allocate int array");
    env.set_int_array_region(&array, 0, &state)
        .expect("failed to fill int array");
    array.into_raw()
}

/// See [Java_dev_twisted4d_app_NativeLib_cubeSetState] -- same contract, for the 4D cube.
#[no_mangle]
pub extern "system" fn Java_dev_twisted4d_app_NativeLib_cube4SetState<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    state: jintArray,
) {
    let state = unsafe { JIntArray::from_raw(state) };
    if let Ok(len) = env.get_array_length(&state) {
        let mut buf = vec![0i32; len as usize];
        if env.get_int_array_region(&state, 0, &mut buf).is_ok() {
            if let Some(cube) = Cube4::from_state(&buf) {
                *cube4().lock().unwrap() = cube;
            }
        }
    }
}
