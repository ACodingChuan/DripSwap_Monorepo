// Routing module - extracted from unified implementation

pub mod dynamic;
pub mod fanout;
pub mod field_extraction;

pub use dynamic::*;
pub use fanout::*;
pub use field_extraction::*;
