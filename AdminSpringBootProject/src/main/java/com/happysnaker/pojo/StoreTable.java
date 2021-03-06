package com.happysnaker.pojo;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * @author happysnakers
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StoreTable  implements Serializable {

  private int storeId;
  private int table;
  private int occupationStatus;
  private int capacity;
}
