use ethabi::ethereum_types::H160;

pub trait Hexable {
    fn to_hex(&self) -> String;
}

impl Hexable for Vec<u8> {
    fn to_hex(&self) -> String {
        format!("0x{}", hex::encode(self))
    }
}

impl Hexable for [u8] {
    fn to_hex(&self) -> String {
        format!("0x{}", hex::encode(self))
    }
}

impl Hexable for H160 {
    fn to_hex(&self) -> String {
        format!("0x{}", hex::encode(self.as_bytes()))
    }
}
